package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.repository.ReviewedReportRow;

/** API#5 응답(stats + items + page 메타). */
public record ReviewedReportListResponse(
    Stats stats, List<Item> items, int page, int size, long totalElements, int totalPages) {

  public static ReviewedReportListResponse from(Stats stats, Page<ReviewedReportRow> page) {
    List<Item> items = page.getContent().stream().map(Item::from).toList();
    return new ReviewedReportListResponse(
        stats, items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** 상단 통계. */
  public record Stats(
      long monthlyReviewCount,
      long previousMonthReviewCount,
      long consultationConvertedCount,
      double consultationConversionRate,
      long totalCount) {
  }

  /** 목록 아이템. */
  public record Item(
      UUID reportId, String caseNo, String title, String accidentType, String region, String outcome,
      LocalDateTime reviewedAt) {

    public static Item from(ReviewedReportRow row) {
      return new Item(
          row.getReportId(), row.getCaseNo(), row.getTitle(), row.getAccidentType(), row.getRegion(),
          row.getOutcome(), row.getReviewedAt());
    }
  }
}
