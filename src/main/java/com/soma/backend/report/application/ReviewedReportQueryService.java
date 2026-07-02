package com.soma.backend.report.application;

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

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.report.application.dto.ReviewedReportItemResult;
import com.soma.backend.report.application.dto.ReviewedReportStatsResult;
import com.soma.backend.report.domain.model.ReviewOutcome;
import com.soma.backend.report.domain.repository.ReportReviewQueryRepository;
import com.soma.backend.report.domain.repository.ReviewedReportRow;

/** API#5 내 검수 내역 + 상단 통계 조회 전용 유스케이스(CQRS). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewedReportQueryService {

  private final ReportReviewQueryRepository reportReviewQueryRepository;

  public ReviewedReportStatsResult getStats(UUID adjusterId, String month) {
    YearMonth targetMonth = parseMonth(month);
    LocalDateTime monthFrom = targetMonth.atDay(1).atStartOfDay();
    LocalDateTime monthTo = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
    YearMonth previousMonth = targetMonth.minusMonths(1);
    LocalDateTime previousFrom = previousMonth.atDay(1).atStartOfDay();
    LocalDateTime previousTo = targetMonth.atDay(1).atStartOfDay();

    long monthlyReviewCount =
        reportReviewQueryRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, monthFrom, monthTo);
    long previousMonthReviewCount =
        reportReviewQueryRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, previousFrom, previousTo);
    long totalCount = reportReviewQueryRepository.countByAdjusterId(adjusterId);
    long consultationConvertedCount = reportReviewQueryRepository.countConsultationConvertedByAdjusterId(adjusterId);
    double conversionRate = totalCount == 0L ? 0.0 : (double) consultationConvertedCount / totalCount;

    return new ReviewedReportStatsResult(
        monthlyReviewCount, previousMonthReviewCount, consultationConvertedCount, conversionRate, totalCount);
  }

  public Page<ReviewedReportItemResult> getReviewedReports(
      UUID adjusterId, String status, String month, Pageable pageable) {
    String outcome = parseStatusFilter(status);
    LocalDateTime monthFrom = null;
    LocalDateTime monthTo = null;
    if (StringUtils.hasText(month)) {
      YearMonth targetMonth = parseMonth(month);
      monthFrom = targetMonth.atDay(1).atStartOfDay();
      monthTo = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
    }
    Page<ReviewedReportRow> rows =
        reportReviewQueryRepository.findReviewedReportRows(adjusterId, outcome, monthFrom, monthTo, pageable);
    return rows.map(row -> new ReviewedReportItemResult(
        row.getReportId(), row.getCaseNo(), row.getTitle(), row.getAccidentType(), row.getRegion(),
        row.getOutcome(), row.getReviewedAt()));
  }

  private String parseStatusFilter(String status) {
    if (!StringUtils.hasText(status) || "ALL".equalsIgnoreCase(status)) {
      return null;
    }
    try {
      return ReviewOutcome.valueOf(status).name();
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
