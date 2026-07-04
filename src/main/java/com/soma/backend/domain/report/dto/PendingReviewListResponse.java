package com.soma.backend.domain.report.dto;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.entity.AmountRange;
import com.soma.backend.domain.report.repository.PendingReviewRow;

/** API#2 응답(item + page 메타). */
public record PendingReviewListResponse(List<Item> items, int page, int size, long totalElements, int totalPages) {

  public static PendingReviewListResponse from(Page<PendingReviewRow> page) {
    List<Item> items = page.getContent().stream().map(Item::from).toList();
    return new PendingReviewListResponse(
        items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** 목록 아이템. */
  public record Item(
      UUID reportId,
      String caseNo,
      String title,
      String accidentType,
      String region,
      String status,
      Long claimedMinAmount,
      Long claimedMaxAmount,
      Long offeredAmount,
      long offerHeadroom,
      long issueCount,
      boolean held) {

    public static Item from(PendingReviewRow row) {
      long offerHeadroom = new AmountRange(
          row.getClaimedMinAmount(), row.getClaimedMaxAmount(), row.getOfferedAmount()).offerHeadroom();
      long issueCount = row.getIssueCount() == null ? 0L : row.getIssueCount();
      boolean held = Boolean.TRUE.equals(row.getHeld());
      return new Item(
          row.getReportId(),
          row.getCaseNo(),
          row.getTitle(),
          row.getAccidentType(),
          row.getRegion(),
          row.getStatus(),
          row.getClaimedMinAmount(),
          row.getClaimedMaxAmount(),
          row.getOfferedAmount(),
          offerHeadroom,
          issueCount,
          held);
    }
  }
}
