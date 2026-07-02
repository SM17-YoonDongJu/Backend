package com.soma.backend.report.presentation.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.report.application.dto.ReviewedReportItemResult;
import com.soma.backend.report.application.dto.ReviewedReportStatsResult;

/** API#5 응답(stats + items + page 메타). */
public record ReviewedReportListResponse(
    Stats stats, List<Item> items, int page, int size, long totalElements, int totalPages) {

  public static ReviewedReportListResponse from(
      ReviewedReportStatsResult stats, Page<ReviewedReportItemResult> page) {
    List<Item> items = page.getContent().stream().map(Item::from).toList();
    return new ReviewedReportListResponse(
        Stats.from(stats), items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** 상단 통계. */
  public record Stats(
      long monthlyReviewCount,
      long previousMonthReviewCount,
      long consultationConvertedCount,
      double consultationConversionRate,
      long totalCount) {

    public static Stats from(ReviewedReportStatsResult result) {
      return new Stats(
          result.monthlyReviewCount(),
          result.previousMonthReviewCount(),
          result.consultationConvertedCount(),
          result.consultationConversionRate(),
          result.totalCount());
    }
  }

  /** 목록 아이템. */
  public record Item(
      UUID reportId, String caseNo, String title, String accidentType, String region, String outcome,
      LocalDateTime reviewedAt) {

    public static Item from(ReviewedReportItemResult result) {
      return new Item(
          result.reportId(), result.caseNo(), result.title(), result.accidentType(), result.region(),
          result.outcome(), result.reviewedAt());
    }
  }
}
