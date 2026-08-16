package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.repository.ReportCardRow;

/**
 * GET /reports 고객 리포트 목록 응답(FE 계약). {@code { list: [...], pagination: {...} }}. 목록은 per-review
 * — 리포트에 달린 report_reviews 1건당 카드 1개다(리포트당 리뷰 N개면 N개 카드). 단 GET /reports(내 리포트)는
 * 아직 검수가 시작되지 않아 리뷰가 0건인 리포트도 카드 1개로 노출하며, 이 카드는 reviewedAt·adjusterNickname이
 * null이고 proposalCount가 0이다. 필드는 camelCase record로 두고 Jackson 전역 설정이 snake_case로 직렬화한다.
 */
public record ReportCardListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Card> list,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Pagination pagination) {

  /**
   * 카드 목록 조립.
   *
   * @param analyses 리포트 id → 분석 처리 상태. 카드 쿼리에 실패 뷰를 조인하면 실패 문서 N건당 카드가 복제되므로
   *                 배치 조회 결과를 여기서 붙인다. 맵에 없는 리포트(= 조회 degrade 포함)는 PROCESSING으로 내린다
   */
  public static ReportCardListResponse from(Page<ReportCardRow> page, Map<UUID, ReportAnalysis> analyses) {
    List<Card> cards = page.getContent().stream()
        .map(row -> Card.from(row, analyses.get(row.reportId())))
        .toList();
    return new ReportCardListResponse(cards, Pagination.from(page));
  }

  /**
   * 카드 항목(리뷰 1건, 리뷰가 없는 미검수 리포트는 리뷰 필드가 빈 카드). status는 고객 노출
   * 매핑(CLOSED→MATCHED), accidentType은 코드값(traffic 등)이다. reviewedAt·adjusterNickname은 그 리뷰
   * 값으로, 리뷰가 없거나 담당 사정사가 없으면 null이다.
   *
   * <p>{@code analysisState}·{@code analysisFailureReason}·{@code analysisFailureMessage}는 REPORTS.status와
   * 다른 축(OCR·AI 처리 파이프라인)이다. 무음 실패(업로드했는데 아무 일도 안 일어남)를 이 목록에서 바로
   * 드러내기 위한 필드로, 실패가 아니면 사유·문구는 null이다(design.md §5-3).
   */
  public record Card(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accidentType,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reportNo,
      @Schema(nullable = true) Integer claimedMinAmount,
      @Schema(nullable = true) Integer claimedMaxAmount,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer proposalCount,
      @Schema(nullable = true) LocalDateTime reviewedAt,
      @Schema(nullable = true) String adjusterNickname,
      @Schema(nullable = true) Integer offeredAmount,
      @Schema(nullable = true) String treatment,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
          description = "분석 처리 상태. PROCESSING | COMPLETED | FAILED | BLOCKED(AI 입력 가드레일 차단) "
              + "| NEEDS_REUPLOAD(OCR 품질 미달 — 문서를 다시 올려야 진행된다)",
          allowableValues = {"PROCESSING", "COMPLETED", "FAILED", "BLOCKED", "NEEDS_REUPLOAD"}) String analysisState,
      @Schema(nullable = true,
          description = "analysis_state가 FAILED일 때만 non-null(BLOCKED·NEEDS_REUPLOAD는 null)")
      String analysisFailureReason,
      @Schema(nullable = true,
          description = "analysis_state가 FAILED·BLOCKED·NEEDS_REUPLOAD일 때만 non-null. 사용자 노출 문구")
      String analysisFailureMessage) {

    public static Card from(ReportCardRow row, ReportAnalysis analysis) {
      return new Card(
          row.reportId(),
          ReportResponseSupport.customerStatus(row.status()),
          row.accidentType() == null ? null : row.accidentType().getValue(),
          ReportResponseSupport.title(row.title()),
          row.createdAt(),
          row.caseNo(),
          row.claimedMinAmount(),
          row.claimedMaxAmount(),
          row.proposalCount() == null ? 0 : row.proposalCount().intValue(),
          row.reviewedAt(),
          row.adjusterNickname(),
          row.offeredAmount(),
          row.treatment(),
          ReportResponseSupport.analysisState(analysis),
          ReportResponseSupport.analysisFailureReason(analysis),
          ReportResponseSupport.analysisFailureMessage(analysis));
    }
  }
}
