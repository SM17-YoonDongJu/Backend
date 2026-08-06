package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.dto.ReviewedReportListResponse;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReviewedReportRow;
import com.soma.backend.domain.report.repository.StatusCount;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#5 내 검수 내역 통계 유스케이스 단위 테스트(§10 경계 케이스). */
@ExtendWith(MockitoExtension.class)
class ReviewedReportQueryServiceTest {

  @Mock
  private ReportReviewRepository reportReviewRepository;

  @InjectMocks
  private ReviewedReportQueryService service;

  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    adjusterId = UUID.randomUUID();
  }

  @Test
  @DisplayName("total_count=0이면 conversion_rate는 0으로 가드된다(0 나눗셈 방지)")
  void conversionRateZeroGuard() {
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(0L);
    given(reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId)).willReturn(0L);

    ReviewedReportListResponse.Stats stats = service.getStats(adjusterId, "2026-05");

    assertThat(stats.consultationConversionRate()).isZero();
    assertThat(stats.totalCount()).isZero();
  }

  @Test
  @DisplayName("conversion_rate = converted / total 로 계산된다")
  void conversionRateComputed() {
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(4L);
    given(reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId)).willReturn(1L);

    ReviewedReportListResponse.Stats stats = service.getStats(adjusterId, "2026-05");

    assertThat(stats.consultationConversionRate()).isEqualTo(0.25);
    assertThat(stats.consultationConvertedCount()).isEqualTo(1L);
  }

  @Test
  @DisplayName("previous_month는 연도 경계를 넘어 1월→전년 12월로 계산된다")
  void previousMonthYearBoundary() {
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(0L);
    given(reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId)).willReturn(0L);

    service.getStats(adjusterId, "2026-01");

    LocalDateTime januaryStart = YearMonth.of(2026, 1).atDay(1).atStartOfDay();
    LocalDateTime februaryStart = YearMonth.of(2026, 2).atDay(1).atStartOfDay();
    LocalDateTime decemberStart = YearMonth.of(2025, 12).atDay(1).atStartOfDay();

    // 이번 달(2026-01) 범위 [2026-01-01, 2026-02-01)
    verify(reportReviewRepository)
        .countByAdjusterIdAndCreatedAtBetween(adjusterId, januaryStart, februaryStart);
    // 직전 달(2025-12) 범위 [2025-12-01, 2026-01-01) — 연도 경계
    verify(reportReviewRepository)
        .countByAdjusterIdAndCreatedAtBetween(adjusterId, decemberStart, januaryStart);
  }

  @Test
  @DisplayName("month 미지정 시 현재월 기준으로 통계를 낸다")
  void nullMonthUsesCurrent() {
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(0L);
    given(reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId)).willReturn(0L);

    service.getStats(adjusterId, null);

    YearMonth current = YearMonth.now();
    LocalDateTime currentStart = current.atDay(1).atStartOfDay();
    LocalDateTime nextStart = current.plusMonths(1).atDay(1).atStartOfDay();
    verify(reportReviewRepository)
        .countByAdjusterIdAndCreatedAtBetween(adjusterId, currentStart, nextStart);
  }

  @Test
  @DisplayName("미래월을 지정하면 VALIDATION_ERROR(400)")
  void futureMonthRejected() {
    YearMonth future = YearMonth.now().plusMonths(1);

    assertThatThrownBy(() -> service.getStats(adjusterId, future.toString()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("형식이 잘못된 month면 VALIDATION_ERROR(400)")
  void malformedMonthRejected() {
    assertThatThrownBy(() -> service.getStats(adjusterId, "2026/01"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("status_counts는 total과 모든 ReviewStatus 키(건수 0 포함)를 total→enum 순으로 담고 집계를 반영한다")
  void statusCountsBuildsFullDistribution() {
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(0L);
    given(reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId)).willReturn(0L);
    given(reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(eq(adjusterId), any(), any()))
        .willReturn(0L);
    given(reportReviewRepository.findReviewedReportRows(eq(adjusterId), any(), any(), any(), any(Pageable.class)))
        .willReturn(new PageImpl<ReviewedReportRow>(List.of()));
    given(reportReviewRepository.countByStatusGrouped(eq(adjusterId), any(), any()))
        .willReturn(List.of(new StatusCount(ReviewStatus.SENT, 5L), new StatusCount(ReviewStatus.REJECTED, 2L)));

    ReviewedReportListResponse result =
        service.getReviewedReports(adjusterId, null, null, PageRequest.of(0, 20));

    Map<String, Integer> counts = result.statusCounts();
    assertThat(counts.get("total")).isEqualTo(7);
    assertThat(counts.get("SENT")).isEqualTo(5);
    assertThat(counts.get("REJECTED")).isEqualTo(2);
    assertThat(counts.get("COUNSELING")).isZero();
    assertThat(counts.get("ACCEPTED")).isZero();
    assertThat(counts.keySet()).containsExactly("total", "SENT", "COUNSELING", "REJECTED", "ACCEPTED");
  }
}
