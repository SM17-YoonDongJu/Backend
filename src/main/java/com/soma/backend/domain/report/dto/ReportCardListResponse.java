package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.repository.ReportCardRow;

/** GET /reports 고객 대시보드 목록 응답(design.md §6). */
public record ReportCardListResponse(List<Card> list, Pagination pagination) {

  public static ReportCardListResponse from(Page<ReportCardRow> page) {
    List<Card> cards = page.getContent().stream().map(Card::from).toList();
    return new ReportCardListResponse(cards, Pagination.from(page));
  }

  public record Card(
      UUID reportId,
      String status,
      String accidentType,
      String title,
      LocalDateTime createdAt,
      String reportNo,
      Long claimedMinAmount,
      Long claimedMaxAmount,
      Long proposalCount,
      LocalDateTime reviewedAt,
      String adjusterNickname,
      Long confirmedMinAmount,
      Long confirmedMaxAmount,
      Double rating) {

    public static Card from(ReportCardRow row) {
      return new Card(
          row.getReportId(), row.getStatus(), row.getAccidentType(), row.getTitle(), row.getCreatedAt(),
          row.getCaseNo(), row.getClaimedMinAmount(), row.getClaimedMaxAmount(), row.getProposalCount(),
          row.getReviewedAt(), row.getAdjusterNickname(), row.getConfirmedMinAmount(),
          row.getConfirmedMaxAmount(), row.getRating());
    }
  }
}
