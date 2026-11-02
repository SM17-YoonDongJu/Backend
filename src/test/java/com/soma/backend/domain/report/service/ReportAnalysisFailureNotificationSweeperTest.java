package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

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
import org.springframework.transaction.CannotCreateTransactionException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.soma.backend.domain.report.entity.AnalysisFailureReason;
import com.soma.backend.domain.report.entity.OcrJobFailureView;
import com.soma.backend.domain.report.entity.OcrJobFailureViewFixture;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportJobFailureViewFixture;
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
@DisplayName("ReportAnalysisFailureNotificationSweeper 단위 테스트")
class ReportAnalysisFailureNotificationSweeperTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 12, 14, 3, 11);

  @Mock
  private OcrJobFailureViewRepository ocrJobFailureViewRepository;
  @Mock
  private TerminalFailureJournalReader terminalFailureJournalReader;
  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ApplicationEventPublisher eventPublisher;

  private SimpleMeterRegistry meterRegistry;
  private ReportAnalysisFailureNotificationSweeper sweeper;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    sweeper = new ReportAnalysisFailureNotificationSweeper(
        ocrJobFailureViewRepository, terminalFailureJournalReader, reportRepository, eventPublisher, meterRegistry);
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

  private double reportJobJournalDegradedCount() {
    return meterRegistry.get("analysis.journal.degraded").tag("journal", "report_job").counter().count();
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
    // OCR 대상이 없으면 배치 상한 전체가 리포트 생성 저널 몫이고, 조회 창도 같다.
    verify(terminalFailureJournalReader).findReportJobFailuresPendingNotification(since.getValue(), 100, List.of());
  }

  @Test
  @DisplayName("스윕이 비활성화되면 조회조차 하지 않는다(테스트·ai 스키마 미가용 환경 no-op)")
  void disabledSweepDoesNothing() {
    ReflectionTestUtils.setField(sweeper, "sweepEnabled", false);

    sweeper.sweep();

    verifyNoInteractions(ocrJobFailureViewRepository, terminalFailureJournalReader, reportRepository, eventPublisher);
  }

  @Test
  @DisplayName("리포트 생성 확정 실패만 있는 리포트도 UNKNOWN 사유로 1회 통지하고 통지 시각을 기록한다")
  void notifiesReportJobFailureWithUnknownReason() {
    // Given: OCR 저널에는 없고 리포트 생성 저널에만 terminal 행이 있다(재전달 한도 소진)
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    given(terminalFailureJournalReader.findReportJobFailuresPendingNotification(any(), anyInt(), anyCollection()))
        .willReturn(List.of(new PendingAnalysisFailureRow(target.getId(), userId)));
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList())).willReturn(Map.of(target.getId(),
        List.of(ReportJobFailureViewFixture.terminal(target.getId(), "llm_unavailable", FAILED_AT))));
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(target));

    // When
    sweeper.sweep();
    sweeper.sweep();

    // Then: 멱등 가드는 OCR 실패와 같은 컬럼이다 — 두 번 돌아도 1건
    ArgumentCaptor<AnalysisFailedEvent> captor = ArgumentCaptor.forClass(AnalysisFailedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().reportId()).isEqualTo(target.getId());
    assertThat(captor.getValue().userId()).isEqualTo(userId);
    assertThat(captor.getValue().reason()).isEqualTo(AnalysisFailureReason.UNKNOWN);
    assertThat(ReflectionTestUtils.getField(target, "analysisFailureNotifiedAt")).isNotNull();
    assertThat(notifiedCount(AnalysisFailureReason.UNKNOWN)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("두 저널 모두에 걸린 리포트는 한 번만 통지하고 대표 사유는 OCR 저널이 정한다(판정 순서 ④ > ⑤)")
  void reportInBothJournalsIsNotifiedOnceWithOcrReason() {
    // Given: 같은 리포트가 두 통지 대상 조회에 모두 나온다(조회 단계에서 빼지만 두 조회 사이 변경에 대비한 방어를 본다)
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    PendingAnalysisFailureRow row = new PendingAnalysisFailureRow(target.getId(), userId);
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt())).willReturn(List.of(row));
    given(terminalFailureJournalReader.findReportJobFailuresPendingNotification(any(), anyInt(), anyCollection()))
        .willReturn(List.of(row));
    given(ocrJobFailureViewRepository.findAllByReportIdInAndTerminalIsTrue(anyList())).willReturn(List.of(
        OcrJobFailureViewFixture.terminal(target.getId(), UUID.randomUUID(), "masking_residual", FAILED_AT)));
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(target));

    // When
    sweeper.sweep();

    // Then: OCR 저널에서 이미 뽑힌 리포트라 리포트 생성 저널 행은 다시 읽지 않는다(판정 순서상 OCR이 정한다)
    ArgumentCaptor<AnalysisFailedEvent> captor = ArgumentCaptor.forClass(AnalysisFailedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().reason()).isEqualTo(AnalysisFailureReason.MASKING_RESIDUAL);
    ArgumentCaptor<List<UUID>> reportIds = ArgumentCaptor.captor();
    verify(reportRepository).findAllByIdIn(reportIds.capture());
    assertThat(reportIds.getValue()).containsExactly(target.getId());
    verify(terminalFailureJournalReader, never()).findTerminalReportJobFailures(anyList());
    verify(terminalFailureJournalReader)
        .findReportJobFailuresPendingNotification(any(), eq(99), eq(List.of(target.getId())));
  }

  @Test
  @DisplayName("리포트 생성 저널을 못 읽어도(012 미적용) OCR 실패 통지는 계속되고, 같은 사이클에 그 저널을 다시 두드리지 않는다")
  void reportJobJournalFailureDoesNotStopOcrNotifications() {
    // Given: 리포트 생성 저널 통지 대상 조회가 실패하고, OCR 저널에는 통지할 리포트가 있다
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    givenPending(target, userId, List.of(
        OcrJobFailureViewFixture.terminal(target.getId(), UUID.randomUUID(), "ocr_error", FAILED_AT)));
    given(terminalFailureJournalReader.findReportJobFailuresPendingNotification(any(), anyInt(), anyCollection()))
        .willThrow(new InvalidDataAccessResourceUsageException("relation \"ai.report_job_failures\" does not exist"));

    // When
    sweeper.sweep();

    // Then: 사이클은 에러로 끝나지 않고 degrade만 센다
    ArgumentCaptor<AnalysisFailedEvent> captor = ArgumentCaptor.forClass(AnalysisFailedEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().reason()).isEqualTo(AnalysisFailureReason.OCR_ERROR);
    assertThat(meterRegistry.get("analysis.failure.sweep.errors").counter().count()).isZero();
    assertThat(reportJobJournalDegradedCount()).isEqualTo(1.0);
    verify(terminalFailureJournalReader, never()).findTerminalReportJobFailures(anyList());
  }

  @Test
  @DisplayName("리포트 생성 저널용 커넥션을 못 얻어도(TransactionException) OCR 실패 통지는 계속된다")
  void reportJobConnectionFailureDoesNotStopOcrNotifications() {
    // Given: REQUIRES_NEW 시작(커넥션 획득)이 실패한다
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    givenPending(target, userId, List.of(
        OcrJobFailureViewFixture.terminal(target.getId(), UUID.randomUUID(), "ocr_error", FAILED_AT)));
    given(terminalFailureJournalReader.findReportJobFailuresPendingNotification(any(), anyInt(), anyCollection()))
        .willThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

    // When
    sweeper.sweep();

    // Then
    verify(eventPublisher).publishEvent(any(AnalysisFailedEvent.class));
    assertThat(meterRegistry.get("analysis.failure.sweep.errors").counter().count()).isZero();
    assertThat(reportJobJournalDegradedCount()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("리포트 생성 실패 행을 다시 읽다가 실패하면 그 리포트는 이번 사이클에 통지하지 않는다(다음 사이클에 다시 본다)")
  void reportJobFailureReloadErrorSkipsThatReportOnly() {
    // Given: 통지 대상 조회는 됐지만 실패 행 재조회가 실패했다
    UUID userId = UUID.randomUUID();
    Report target = report(userId);
    given(terminalFailureJournalReader.findReportJobFailuresPendingNotification(any(), anyInt(), anyCollection()))
        .willReturn(List.of(new PendingAnalysisFailureRow(target.getId(), userId)));
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList()))
        .willThrow(new InvalidDataAccessResourceUsageException("permission denied for table report_job_failures"));
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(target));

    // When
    sweeper.sweep();

    // Then: 판정 근거가 없으니 PROCESSING — 통지하지 않고 통지 시각도 남기지 않는다
    verify(eventPublisher, never()).publishEvent(any(AnalysisFailedEvent.class));
    assertThat(ReflectionTestUtils.getField(target, "analysisFailureNotifiedAt")).isNull();
    assertThat(reportJobJournalDegradedCount()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("배치 상한 100은 두 저널 합계다 — 리포트 생성 저널은 OCR이 뽑은 리포트를 빼고 남은 자리만큼만 조회한다")
  void reportJobPendingUsesRemainingBatchSlots() {
    // Given: OCR 저널에서 30건을 뽑았다
    List<PendingAnalysisFailureRow> ocrPending = IntStream.range(0, 30)
        .mapToObj(index -> new PendingAnalysisFailureRow(UUID.randomUUID(), UUID.randomUUID()))
        .toList();
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt())).willReturn(ocrPending);

    // When
    sweeper.sweep();

    // Then: 남은 70자리를 겹치는 리포트가 차지하지 않도록 OCR이 뽑은 30건을 조회 단계에서 뺀다
    List<UUID> ocrSelected = ocrPending.stream().map(PendingAnalysisFailureRow::reportId).toList();
    verify(terminalFailureJournalReader).findReportJobFailuresPendingNotification(any(), eq(70), eq(ocrSelected));
  }

  @Test
  @DisplayName("OCR 대상만으로 배치가 차면 리포트 생성 저널 대상은 이번 사이클에 조회하지 않는다")
  void fullOcrBatchSkipsReportJobPending() {
    List<PendingAnalysisFailureRow> ocrPending = IntStream.range(0, 100)
        .mapToObj(index -> new PendingAnalysisFailureRow(UUID.randomUUID(), UUID.randomUUID()))
        .toList();
    given(ocrJobFailureViewRepository.findPendingNotification(any(), anyInt())).willReturn(ocrPending);

    sweeper.sweep();

    verify(terminalFailureJournalReader, never())
        .findReportJobFailuresPendingNotification(any(), anyInt(), anyCollection());
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
