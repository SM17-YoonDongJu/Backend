package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.soma.backend.domain.report.entity.AnalysisFailureReason;
import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.OcrJobFailureViewFixture;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.event.AnalysisFailedEvent;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.PendingAnalysisFailureRow;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 분석 실패 알림 스윕 단위 테스트(design.md §15 Q8 + §7-3 사양).
 *
 * <p>멱등 가드(리포트당 1회)·대표 사유 산출·조회 창(7일)·배치 상한(100)·비활성화 스위치·관측성 카운터를
 * 실제 DB 없이 고정한다. 통지 시각 기록과 이벤트 발행이 한 쌍으로만 일어나야 중복 푸시가 나가지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnalysisFailureNotificationSweeper 단위 테스트")
class AnalysisFailureNotificationSweeperTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 12, 14, 3, 11);

  @Mock
  private OcrJobFailureViewRepository ocrJobFailureViewRepository;
  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ApplicationEventPublisher eventPublisher;

  private SimpleMeterRegistry meterRegistry;
  private AnalysisFailureNotificationSweeper sweeper;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    sweeper = new AnalysisFailureNotificationSweeper(
        ocrJobFailureViewRepository, reportRepository, eventPublisher, meterRegistry);
    // 운영 기본값과 동일하게 켠 상태로 검증한다(@Value 주입은 단위 테스트에서 일어나지 않는다).
    ReflectionTestUtils.setField(sweeper, "sweepEnabled", true);
  }

  private static Report report(UUID userId) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(report, "userId", userId);
    return report;
  }

  private void givenPending(Report report, UUID userId, List<OcrJobFailureView> failures) {
    UUID reportId = report.getId();
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt()))
        .willReturn(List.of(new PendingAnalysisFailureRow(reportId, userId)));
    given(ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(anyList())).willReturn(failures);
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(report));
  }

  private double notifiedCount(AnalysisFailureReason reason) {
    return meterRegistry.get("analysis.failure.notified").tag("reason", reason.name()).counter().count();
  }

  @Test
  @DisplayName("Q8 — 스윕을 2회 연속 실행해도 리포트당 알림 이벤트는 1건만 발행된다(통지 시각 가드)")
  void secondSweepDoesNotNotifyAgain() {
    // Given: 같은 리포트가 두 사이클 모두 조회돼도(경쟁·중복 실행) 통지는 1회여야 한다
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    givenPending(target, userId, List.of(
        OcrJobFailureViewFixture.terminal(target.getId(), UUID.randomUUID(), "unreadable_file", FAILED_AT)));

    // When
    sweeper.sweep();
    sweeper.sweep();

    // Then
    verify(eventPublisher).publishEvent(any(AnalysisFailedEvent.class));
    assertThat(ReflectionTestUtils.getField(target, "analysisFailureNotifiedAt")).isNotNull();
    assertThat(notifiedCount(AnalysisFailureReason.UNREADABLE_FILE)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("확정 실패 리포트는 소유자·리포트·대표 사유를 담은 AnalysisFailedEvent를 발행한다")
  void publishesEventWithOwnerAndRepresentativeReason() {
    // Given: 한 리포트에 마스킹 잔류 + 판독 불가가 섞였다
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    givenPending(target, userId, List.of(
        OcrJobFailureViewFixture.terminal(target.getId(), UUID.randomUUID(), "unreadable_file", FAILED_AT),
        OcrJobFailureViewFixture.terminal(target.getId(), UUID.randomUUID(), "masking_residual", FAILED_AT)));

    // When
    sweeper.sweep();

    // Then: 배치 경계에서 사유가 잘리지 않도록 리포트의 실패 행 전부로 대표를 계산한다(§8 E5)
    ArgumentCaptor<AnalysisFailedEvent> captor = ArgumentCaptor.forClass(AnalysisFailedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().userId()).isEqualTo(userId);
    assertThat(captor.getValue().reportId()).isEqualTo(target.getId());
    assertThat(captor.getValue().reason()).isEqualTo(AnalysisFailureReason.MASKING_RESIDUAL);
    assertThat(notifiedCount(AnalysisFailureReason.MASKING_RESIDUAL)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("조회 후 AI 초안이 생긴 리포트(정상 회복)는 통지하지 않고 시각도 기록하지 않는다")
  void skipsRecoveredReport() {
    // Given: 조회와 통지 사이에 성공한 리포트 — 성공이 실패를 이긴다(§8 E3)
    UUID userId = UUID.randomUUID();
    Report recovered = report(userId);
    ReflectionTestUtils.setField(recovered, "applicableGuarantees", List.of("상해후유장해"));
    givenPending(recovered, userId, List.of(
        OcrJobFailureViewFixture.terminal(recovered.getId(), UUID.randomUUID(), "ocr_error", FAILED_AT)));

    // When
    sweeper.sweep();

    // Then
    verify(eventPublisher, never()).publishEvent(any(AnalysisFailedEvent.class));
    assertThat(ReflectionTestUtils.getField(recovered, "analysisFailureNotifiedAt")).isNull();
  }

  @Test
  @DisplayName("실패 행이 모두 사라진 리포트(회복)는 통지하지 않는다")
  void skipsReportWithoutRemainingFailures() {
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    givenPending(target, userId, List.of());

    sweeper.sweep();

    verify(eventPublisher, never()).publishEvent(any(AnalysisFailedEvent.class));
  }

  @Test
  @DisplayName("조회 창은 now-7일, 배치 상한은 100이다(무기한 스캔·동기 FCM 폭주 방지)")
  void queriesSevenDayLookbackWithBatchLimit() {
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt())).willReturn(List.of());

    LocalDateTime before = LocalDateTime.now().minusDays(7);
    sweeper.sweep();
    LocalDateTime after = LocalDateTime.now().minusDays(7);

    ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(ocrJobFailureViewRepository).findPendingNotification(since.capture(), limit.capture());
    assertThat(since.getValue()).isBetween(before, after);
    assertThat(limit.getValue()).isEqualTo(100);
  }

  @Test
  @DisplayName("스윕이 비활성화되면 조회조차 하지 않는다(테스트·ai 스키마 미가용 환경 no-op)")
  void disabledSweepDoesNothing() {
    ReflectionTestUtils.setField(sweeper, "sweepEnabled", false);

    sweeper.sweep();

    verifyNoInteractions(ocrJobFailureViewRepository, reportRepository, eventPublisher);
  }

  @Test
  @DisplayName("조회가 실패하면 에러 카운터를 올리고 예외를 그대로 던진다(트랜잭션 오염 방지 + 지표 노출)")
  void countsAndRethrowsSweepError() {
    // Given: GRANT 미적용 등으로 ai 저널 조회가 실패하는 상황
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt()))
        .willThrow(new InvalidDataAccessResourceUsageException("relation \"ai.ocr_job_failures\" does not exist"));

    // When & Then: 삼키면 커밋 시 UnexpectedRollbackException이 나므로 다시 던져야 한다
    assertThatThrownBy(() -> sweeper.sweep())
        .isInstanceOf(InvalidDataAccessResourceUsageException.class);
    assertThat(meterRegistry.get("analysis.failure.sweep.errors").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("조회된 리포트가 사라졌으면(삭제 경쟁) 건너뛰고 예외를 내지 않는다")
  void skipsMissingReportWithoutFailing() {
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt()))
        .willReturn(List.of(new PendingAnalysisFailureRow(reportId, userId)));
    given(ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(anyList())).willReturn(List.of());
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of());

    sweeper.sweep();

    verify(eventPublisher, never()).publishEvent(any(AnalysisFailedEvent.class));
  }
}
