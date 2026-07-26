package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.repository.ReportCardRow;

/**
 * GET /reports 고객 대시보드 목록 응답(design.md §6, FE 계약). {@code { list: [...], pagination: {...} }}.
 * 필드는 camelCase record로 두고 Jackson 전역 설정이 snake_case로 직렬화한다.
 */
public record ReportCardListResponse(List<Card> list, Pagination pagination) {

  public static ReportCardListResponse from(Page<ReportCardRow> page) {
    List<Card> cards = page.getContent().stream().map(Card::from).toList();
    return new ReportCardListResponse(cards, Pagination.from(page));
  }

  /**
   * 카드 항목. status는 REPORTS.status 이름(AWAITING_INSPECTION 등), accidentType은 소문자 값(traffic 등).
   * ACCEPTED 제안이 없으면 reviewedAt·adjusterNickname·confirmedMin/Max·rating은 null이다.
   */
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
          row.reportId(),
          row.status() == null ? null : row.status().name(),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          row.title(),
          row.createdAt(),
          row.caseNo(),
          row.claimedMinAmount(),
          row.claimedMaxAmount(),
          row.proposalCount() == null ? 0L : row.proposalCount(),
          row.reviewedAt(),
          row.adjusterNickname(),
          row.confirmedMinAmount(),
          row.confirmedMaxAmount(),
          row.rating() == null ? null : row.rating().doubleValue());
    }
  }
}
