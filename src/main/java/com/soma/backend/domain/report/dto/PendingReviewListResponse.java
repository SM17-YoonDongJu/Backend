package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.entity.AmountRange;
import com.soma.backend.domain.report.repository.PendingReviewRow;

/** API#2 응답(item + page 메타). */
public record PendingReviewListResponse(List<Item> items, int page, int size, long totalElements, int totalPages) {

  public static PendingReviewListResponse from(
      Page<PendingReviewRow> page, Map<UUID, String> reportReviewStatusByReportId) {
    List<Item> items = page.getContent().stream()
        .map(row -> Item.from(row, reportReviewStatusByReportId.get(row.reportId())))
        .toList();
    return new PendingReviewListResponse(
        items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /**
   * 목록 아이템. {@code status}는 리포트 생명주기 상태(REPORTS.status), {@code reportReviewStatus}는 요청
   * 사정사 본인의 이 리포트 검수 상태(REPORT_REVIEWS.status)로 본인 검수가 없으면 null이다.
   */
  public record Item(
      UUID reportId,
      String caseNo,
      String title,
      String accidentType,
      List<String> region,
      String status,
      String reportReviewStatus,
      Long claimedMinAmount,
      Long claimedMaxAmount,
      Long offeredAmount,
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
          row.title(),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          row.region(),
          row.status() == null ? null : row.status().name(),
          reportReviewStatus,
          row.claimedMinAmount(),
          row.claimedMaxAmount(),
          row.offeredAmount(),
          offerHeadroom,
          issueCount,
          held,
          row.createdAt());
    }
  }
}
