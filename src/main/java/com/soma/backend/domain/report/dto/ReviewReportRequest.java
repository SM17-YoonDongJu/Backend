package com.soma.backend.domain.report.dto;

import java.util.List;
import java.util.UUID;

/** API#4 요청 바디(snake_case 수신 → camelCase 필드). */
public record ReviewReportRequest(
    Long estimateMinAmount,
    Long estimateMaxAmount,
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<String> basisTermsPrecedents,
    List<IssueReview> issues,
    String review) {

  /**
   * issue 단위 검수 결과(부분 반영). reviewIssueId(report_review_issues.id)가 있으면 그 행을 갱신하고, 없으면
   * issueId(report_issue_id=AI 쟁점)로 매칭한다. issueId가 null이면 ADDED(사정사 신규 쟁점)이며 title·description이
   * 필수다. impactAmount는 사정사가 수정(MODIFIED)·신규(ADDED) 시 직접 입력하는 영향 금액(선택). 보내지 않은 쟁점은
   * 유지된다(삭제 없음).
   */
  public record IssueReview(
      UUID reviewIssueId, UUID issueId, String reviewStatus, String title, String description,
      Long impactAmount, String adjusterOpinion, String modifiedReason, String excludedReason) {
  }
}
