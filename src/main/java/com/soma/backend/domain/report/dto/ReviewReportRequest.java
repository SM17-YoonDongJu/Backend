package com.soma.backend.domain.report.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API#4 요청 바디(snake_case 수신 → camelCase 필드). 부분 반영(upsert) 계약이라 모든 필드가 선택이다 —
 * 보내지 않은 필드는 기존 값을 유지한다({@code ReportReviewCommandService.review} 참고).
 */
public record ReviewReportRequest(
    @Schema(nullable = true) Integer estimateMinAmount,
    @Schema(nullable = true) Integer estimateMaxAmount,
    @Schema(nullable = true) List<String> applicableGuarantees,
    @Schema(nullable = true) List<String> omittedSpecialContract,
    @Schema(nullable = true) List<String> basisTermsPrecedents,
    @Schema(nullable = true) List<IssueReview> issues,
    @Schema(nullable = true) String review) {

  /**
   * issue 단위 검수 결과(부분 반영). reviewIssueId(report_issues_reviews.id)가 있으면 그 행을 갱신하고, 없으면
   * issueId(report_issue_id=AI 쟁점)로 매칭한다. issueId가 null이면 ADDED(사정사 신규 쟁점)이며 title·description이
   * 필수다. impactAmount는 사정사가 수정(MODIFIED)·신규(ADDED) 시 직접 입력하는 영향 금액(선택). 보내지 않은 쟁점은
   * 유지된다(삭제 없음).
   */
  public record IssueReview(
      @Schema(nullable = true, description = "report_issues_reviews.id. 있으면 그 행을 갱신") UUID reviewIssueId,
      @Schema(nullable = true,
          description = "AI 쟁점(report_issues.id). ADDED(신규 쟁점)면 null, ACCEPTED·MODIFIED·EXCLUDED면 필수")
          UUID issueId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ACCEPTED | MODIFIED | EXCLUDED | ADDED")
          String reviewStatus,
      @Schema(nullable = true, description = "reviewStatus=ADDED면 필수") String title,
      @Schema(nullable = true, description = "reviewStatus=ADDED면 필수") String description,
      @Schema(nullable = true) Integer impactAmount,
      @Schema(nullable = true) String adjusterOpinion,
      @Schema(nullable = true, description = "reviewStatus=MODIFIED면 필수") String modifiedReason,
      @Schema(nullable = true, description = "reviewStatus=EXCLUDED면 필수") String excludedReason) {
  }
}
