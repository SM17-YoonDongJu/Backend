package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.event.ReportNeedsReuploadEvent;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * NEEDS_REUPLOAD(OCR 품질 미달) 알림 스윕 단위 테스트. {@link BlockedReportNotificationSweeperTest}와 같은
 * 원칙을 전용 멱등 가드 컬럼(needs_reupload_notified_at)에 대해 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NeedsReuploadNotificationSweeper 단위 테스트")
class NeedsReuploadNotificationSweeperTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ApplicationEventPublisher eventPublisher;

  private SimpleMeterRegistry meterRegistry;
  private NeedsReuploadNotificationSweeper sweeper;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    sweeper = new NeedsReuploadNotificationSweeper(reportRepository, eventPublisher, meterRegistry);
    ReflectionTestUtils.setField(sweeper, "sweepEnabled", true);
  }

  private static Report needsReuploadReport(UUID userId) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(report, "userId", userId);
    ReflectionTestUtils.setField(report, "status", ReportStatus.NEEDS_REUPLOAD);
    return report;
  }

  private double notifiedCount() {
    return meterRegistry.get("report.needs_reupload.notified").counter().count();
  }

  @Test
  @DisplayName("스윕을 2회 연속 실행해도 리포트당 알림 이벤트는 1건만 발행된다(통지 시각 가드)")
  void secondSweepDoesNotNotifyAgain() {
    UUID userId = UUID.randomUUID();
    Report target = needsReuploadReport(userId);
    given(reportRepository.findAllByStatusAndNeedsReuploadNotifiedAtIsNull(any(), any(Pageable.class)))
        .willReturn(List.of(target));

    sweeper.sweep();
    sweeper.sweep();

    verify(eventPublisher).publishEvent(any(ReportNeedsReuploadEvent.class));
    assertThat(ReflectionTestUtils.getField(target, "needsReuploadNotifiedAt")).isNotNull();
    assertThat(notifiedCount()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("재업로드가 필요한 리포트의 소유자·리포트를 담은 ReportNeedsReuploadEvent를 발행한다")
  void publishesEventWithOwnerAndReport() {
    UUID userId = UUID.randomUUID();
    Report target = needsReuploadReport(userId);
    given(reportRepository.findAllByStatusAndNeedsReuploadNotifiedAtIsNull(any(), any(Pageable.class)))
        .willReturn(List.of(target));

    sweeper.sweep();

    ArgumentCaptor<ReportNeedsReuploadEvent> captor = ArgumentCaptor.forClass(ReportNeedsReuploadEvent.class);
    verify(eventPublisher).publishEvent(captor.capture());
    assertThat(captor.getValue().userId()).isEqualTo(userId);
    assertThat(captor.getValue().reportId()).isEqualTo(target.getId());
  }

  @Test
  @DisplayName("status=NEEDS_REUPLOAD·needsReuploadNotifiedAt IS NULL·배치 상한 100으로 reports를 직접 조회한다")
  void queriesReportsDirectlyWithBatchLimit() {
    given(reportRepository.findAllByStatusAndNeedsReuploadNotifiedAtIsNull(any(), any(Pageable.class)))
        .willReturn(List.of());

    sweeper.sweep();

    ArgumentCaptor<ReportStatus> statusCaptor = ArgumentCaptor.forClass(ReportStatus.class);
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(reportRepository)
        .findAllByStatusAndNeedsReuploadNotifiedAtIsNull(statusCaptor.capture(), pageableCaptor.capture());
    assertThat(statusCaptor.getValue()).isEqualTo(ReportStatus.NEEDS_REUPLOAD);
    assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 100));
  }

  @Test
  @DisplayName("스윕이 비활성화되면 조회조차 하지 않는다")
  void disabledSweepDoesNothing() {
    ReflectionTestUtils.setField(sweeper, "sweepEnabled", false);

    sweeper.sweep();

    verifyNoInteractions(reportRepository, eventPublisher);
  }

  @Test
  @DisplayName("조회가 실패하면 에러 카운터를 올리고 예외를 그대로 던진다(트랜잭션 오염 방지 + 지표 노출)")
  void countsAndRethrowsSweepError() {
    given(reportRepository.findAllByStatusAndNeedsReuploadNotifiedAtIsNull(any(), any(Pageable.class)))
        .willThrow(new InvalidDataAccessResourceUsageException("boom"));

    assertThatThrownBy(() -> sweeper.sweep()).isInstanceOf(InvalidDataAccessResourceUsageException.class);
    assertThat(meterRegistry.get("report.needs_reupload.sweep.errors").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("조회 대상이 없으면 이벤트를 발행하지 않는다")
  void skipsWhenNoPendingReports() {
    given(reportRepository.findAllByStatusAndNeedsReuploadNotifiedAtIsNull(any(), any(Pageable.class)))
        .willReturn(List.of());

    sweeper.sweep();

    verify(eventPublisher, never()).publishEvent(any(ReportNeedsReuploadEvent.class));
  }
}
