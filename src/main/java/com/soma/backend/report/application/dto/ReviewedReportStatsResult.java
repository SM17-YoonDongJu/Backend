package com.soma.backend.report.application.dto;

/** API#5 상단 통계 결과. */
public record ReviewedReportStatsResult(
    long monthlyReviewCount,
    long previousMonthReviewCount,
    long consultationConvertedCount,
    double consultationConversionRate,
    long totalCount) {
}
