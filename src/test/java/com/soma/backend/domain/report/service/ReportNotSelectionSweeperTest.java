package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;

/** 미채택(NOT_SELECTED) 자동 전이 스윕 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class ReportNotSelectionSweeperTest {

  @Mock
  private ReportRepository reportRepository;

  @InjectMocks
  private ReportNotSelectionSweeper sweeper;

  private static Report reportWithStatus(ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  @Test
  @DisplayName("전이 대상 리포트를 모두 NOT_SELECTED로 전이한다")
  void marksExpiredReportsNotSelected() {
    Report inspection = reportWithStatus(ReportStatus.AWAITING_INSPECTION);
    Report adoption = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    given(reportRepository.findExpiredForNotSelection(any(), any()))
        .willReturn(List.of(inspection, adoption));

    sweeper.sweep();

    assertThat(inspection.getStatus()).isEqualTo(ReportStatus.NOT_SELECTED);
    assertThat(adoption.getStatus()).isEqualTo(ReportStatus.NOT_SELECTED);
  }

  @Test
  @DisplayName("조회 기준은 검수 대기·채택 대기 상태 + now-7일 threshold다")
  void queriesSevenDayThresholdAndPendingSources() {
    given(reportRepository.findExpiredForNotSelection(any(), any())).willReturn(List.of());

    LocalDateTime before = LocalDateTime.now().minusDays(7);
    sweeper.sweep();
    LocalDateTime after = LocalDateTime.now().minusDays(7);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<ReportStatus>> sources = ArgumentCaptor.forClass(Collection.class);
    ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reportRepository).findExpiredForNotSelection(sources.capture(), threshold.capture());
    assertThat(sources.getValue())
        .containsExactlyInAnyOrder(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION);
    assertThat(threshold.getValue()).isBetween(before, after);
  }
}
