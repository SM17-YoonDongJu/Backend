package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.repository.ReviewedReportRow;

/**
 * API#5 응답(stats + status_counts + items + page 메타).
 *
 * <p>{@code statusCounts}는 필터 탭 건수 배지용으로 {@code total}과 {@code ReviewStatus} enum 값을 키로 한
 * 상태별 건수를 담는다(예: {@code {"total":12,"SENT":5,...}}). 월 필터는 반영하되 status 탭 필터는 미반영이라
 * 어떤 탭이 선택돼도 전체 분포를 보여준다(FE #94).
 */
public record ReviewedReportListResponse(
    Stats stats, Map<String, Long> statusCounts, List<Item> items,
    int page, int size, long totalElements, int totalPages) {

  public static ReviewedReportListResponse from(
      Stats stats, Map<String, Long> statusCounts, Page<ReviewedReportRow> page) {
    List<Item> items = page.getContent().stream().map(Item::from).toList();
    return new ReviewedReportListResponse(
        stats, statusCounts, items,
        page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /**
   * 상단 통계. consultationConvertedCount·consultationConversionRate는 현재 항상 0이다 —
   * COUNSELING 전이(채팅방 생성)가 별도 티켓이라 본 티켓에서는 채워지지 않는다(m1).
   */
  public record Stats(
      long monthlyReviewCount,
      long previousMonthReviewCount,
      long consultationConvertedCount,
      double consultationConversionRate,
      long totalCount) {
  }

  /**
   * 목록 아이템. {@code accidentType}·{@code status}는 DB 문자열 값으로 내린다. {@code confirmedMinAmount}·
   * {@code confirmedMaxAmount}는 사정사 추산 확정 범위(REPORT_REVIEWS.estimate_min/max_amount), {@code rating}은
   * 고객의 사건별 평점(미작성 시 null)이다(FE #59).
   */
  public record Item(
      UUID reportId, String caseNo, String title, String accidentType, List<String> region, String status,
      LocalDateTime reviewedAt, Long confirmedMinAmount, Long confirmedMaxAmount, Integer rating) {

    public static Item from(ReviewedReportRow row) {
      return new Item(
          row.reportId(),
          row.caseNo(),
          row.title(),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          row.region(),
          row.status() == null ? null : row.status().name(),
          row.reviewedAt(),
          row.confirmedMinAmount(),
          row.confirmedMaxAmount(),
          row.rating());
    }
  }
}
