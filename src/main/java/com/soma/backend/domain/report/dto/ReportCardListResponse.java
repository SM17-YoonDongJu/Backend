package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.soma.backend.domain.report.repository.ReportCardRow;

/**
 * GET /reports 고객 리포트 목록 응답(FE 계약). {@code { list: [...], pagination: {...} }}. 목록은 per-review
 * — 리포트에 달린 report_reviews 1건당 카드 1개다(리포트당 리뷰 N개면 N개 카드). 필드는 camelCase record로
 * 두고 Jackson 전역 설정이 snake_case로 직렬화한다.
 */
public record ReportCardListResponse(List<Card> list, Pagination pagination) {

  public static ReportCardListResponse from(Page<ReportCardRow> page) {
    List<Card> cards = page.getContent().stream().map(Card::from).toList();
    return new ReportCardListResponse(cards, Pagination.from(page));
  }

  /**
   * 카드 항목(리뷰 1건). status는 고객 노출 매핑(CLOSED→MATCHED), accidentType은 코드값(traffic 등)이다.
   * reviewedAt·adjusterNickname은 그 리뷰 값으로, 담당 사정사가 없으면 null이다.
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
      Long offeredAmount,
      String treatment) {

    public static Card from(ReportCardRow row) {
      return new Card(
          row.reportId(),
          ReportResponseSupport.customerStatus(row.status()),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          row.title(),
          row.createdAt(),
          row.caseNo(),
          row.claimedMinAmount(),
          row.claimedMaxAmount(),
          row.proposalCount() == null ? 0L : row.proposalCount(),
          row.reviewedAt(),
          row.adjusterNickname(),
          row.offeredAmount(),
          row.treatment());
    }
  }
}
