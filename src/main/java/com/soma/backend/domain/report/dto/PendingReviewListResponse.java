package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
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
      List<String> region,
      String status,
      Long claimedMinAmount,
      Long claimedMaxAmount,
      Long offeredAmount,
      long offerHeadroom,
      long issueCount,
      boolean held,
      LocalDateTime createdAt) {

    public static Item from(PendingReviewRow row) {
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
