package com.soma.backend.report.presentation.dto;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.report.application.dto.PendingReviewItemResult;

/** API#2 응답(item + page 메타). */
public record PendingReviewListResponse(List<Item> items, int page, int size, long totalElements, int totalPages) {

  public static PendingReviewListResponse from(Page<PendingReviewItemResult> page) {
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

    public static Item from(PendingReviewItemResult result) {
      return new Item(
          result.reportId(),
          result.caseNo(),
          result.title(),
          result.accidentType(),
          result.region(),
          result.status(),
          result.claimedMinAmount(),
          result.claimedMaxAmount(),
          result.offeredAmount(),
          result.offerHeadroom(),
          result.issueCount(),
          result.held());
    }
  }
}
