package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReviewedReportListResponse;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReviewedReportRow;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#5 내 검수 내역 + 상단 통계 조회 전용 유스케이스(CQRS). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewedReportQueryService {

  private final ReportReviewRepository reportReviewRepository;

  public ReviewedReportListResponse.Stats getStats(UUID adjusterId, String month) {
    YearMonth targetMonth = parseMonth(month);
    LocalDateTime monthFrom = targetMonth.atDay(1).atStartOfDay();
    LocalDateTime monthTo = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
    YearMonth previousMonth = targetMonth.minusMonths(1);
    LocalDateTime previousFrom = previousMonth.atDay(1).atStartOfDay();
    LocalDateTime previousTo = targetMonth.atDay(1).atStartOfDay();

    long monthlyReviewCount =
        reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, monthFrom, monthTo);
    long previousMonthReviewCount =
        reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, previousFrom, previousTo);
    long totalCount = reportReviewRepository.countByAdjusterId(adjusterId);
    long consultationConvertedCount = reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId);
    double conversionRate = totalCount == 0L ? 0.0 : (double) consultationConvertedCount / totalCount;

    return new ReviewedReportListResponse.Stats(
        monthlyReviewCount, previousMonthReviewCount, consultationConvertedCount, conversionRate, totalCount);
  }

  public ReviewedReportListResponse getReviewedReports(
      UUID adjusterId, String status, String month, Pageable pageable) {
    ReviewedReportListResponse.Stats stats = getStats(adjusterId, month);

    String outcome = parseStatusFilter(status);
    LocalDateTime monthFrom = null;
    LocalDateTime monthTo = null;
    if (StringUtils.hasText(month)) {
      YearMonth targetMonth = parseMonth(month);
      monthFrom = targetMonth.atDay(1).atStartOfDay();
      monthTo = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
    }
    Page<ReviewedReportRow> rows =
        reportReviewRepository.findReviewedReportRows(adjusterId, outcome, monthFrom, monthTo, pageable);
    return ReviewedReportListResponse.from(stats, rows);
  }

  private String parseStatusFilter(String status) {
    if (!StringUtils.hasText(status) || "ALL".equalsIgnoreCase(status)) {
      return null;
    }
    try {
      return ReviewStatus.valueOf(status).name();
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }

  private YearMonth parseMonth(String month) {
    if (!StringUtils.hasText(month)) {
      return YearMonth.now();
    }
    try {
      YearMonth parsed = YearMonth.parse(month);
      if (parsed.isAfter(YearMonth.now())) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
      }
      return parsed;
    } catch (DateTimeParseException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
