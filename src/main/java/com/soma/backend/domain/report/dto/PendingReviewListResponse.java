package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.entity.AmountRange;
import com.soma.backend.domain.report.repository.PendingReviewRow;

/** API#2 응답. 노션 스펙 정합 — {@code { list: [...], pagination: {...} }} 구조(page는 1-based). */
public record PendingReviewListResponse(List<Item> list, Pagination pagination) {

  public static PendingReviewListResponse from(
      Page<PendingReviewRow> page, Map<UUID, String> reportReviewStatusByReportId) {
    List<Item> list = page.getContent().stream()
        .map(row -> Item.from(row, reportReviewStatusByReportId.get(row.reportId())))
        .toList();
    Pagination pagination = new Pagination(
        page.getNumber() + 1,
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        page.hasNext());
    return new PendingReviewListResponse(list, pagination);
  }

  /** 페이지 메타(page는 1-based, has_next = page &lt; total_pages). */
  public record Pagination(int page, int size, long totalElements, int totalPages, boolean hasNext) {
  }

  /**
   * 목록 아이템. {@code status}는 리포트 생명주기 상태(REPORTS.status), {@code reportReviewStatus}는 요청
   * 사정사 본인의 이 리포트 검수 상태(REPORT_REVIEWS.status)로 본인 검수가 없으면 null이다.
   *
   * <p>보험사 제시액 원값(offered_amount)은 금소법상 노출하지 않는다 — 응답에 싣지 않고 {@code offerHeadroom}
   * (청구액 대비 여유) 계산에만 내부적으로 사용한다.
   */
  public record Item(
      UUID reportId,
      String caseId,
      String title,
      String accidentType,
      String region,
      String status,
      String reportReviewStatus,
      Integer claimedMinAmount,
      Integer claimedMaxAmount,
      long offerHeadroom,
      long issueCount,
      boolean held,
      LocalDateTime createdAt) {

    public static Item from(PendingReviewRow row, String reportReviewStatus) {
      long offerHeadroom = new AmountRange(
          row.claimedMinAmount(), row.claimedMaxAmount(), row.offeredAmount()).offerHeadroom();
      long issueCount = row.issueCount() == null ? 0L : row.issueCount();
      boolean held = Boolean.TRUE.equals(row.held());
      return new Item(
          row.reportId(),
          row.caseNo(),
          ReportResponseSupport.title(row.title()),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          joinRegion(row.region()),
          row.status() == null ? null : row.status().name(),
          reportReviewStatus,
          row.claimedMinAmount(),
          row.claimedMaxAmount(),
          offerHeadroom,
          issueCount,
          held,
          row.createdAt());
    }
  }

  /** region(text[])을 프론트 계약(단일 string)에 맞춰 조인한다. null·빈 리스트는 빈 문자열. */
  private static String joinRegion(List<String> region) {
    if (region == null || region.isEmpty()) {
      return "";
    }
    return String.join("·", region);
  }
}
