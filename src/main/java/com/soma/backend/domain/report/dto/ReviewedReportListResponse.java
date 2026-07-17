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
   * 목록 아이템. confirmedMinAmount·confirmedMaxAmount는 사정사가 확정한 예상 보상 금액 범위
   * (report_reviews.estimate_min/max_amount)로, 미확정 건은 null이다.
   *
   * <p>rating은 "이 리포트에 대한 사용자 평점" 소스가 현재 스키마에 없어 항상 null이다 —
   * ADJUSTER_REVIEW(adjuster_reviews)는 (user_id, adjuster_id)로만 묶인 사정사 단위 평가라
   * 리포트별 축이 아니고(glossary §13, report_id 컬럼 없음), adjuster_profiles.rating_mean(V8)은
   * 집계값이라 대체 불가다. 리포트별 평점 테이블은 별도 ERD 티켓에서 도입한다(응답 필드만 선반영).
   */
  public record Item(
      UUID reportId, String caseNo, String title, String accidentType, String region, String status,
      LocalDateTime reviewedAt, Long confirmedMinAmount, Long confirmedMaxAmount, Integer rating) {

    public static Item from(ReviewedReportRow row) {
      return new Item(
          row.getReportId(), row.getCaseNo(), row.getTitle(), row.getAccidentType(), row.getRegion(),
          row.getStatus(), row.getReviewedAt(), row.getConfirmedMinAmount(), row.getConfirmedMaxAmount(), null);
    }
  }
}
