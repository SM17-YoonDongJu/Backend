package com.soma.backend.domain.report.dto;

import java.util.List;
import java.util.UUID;

/** API#4 요청 바디(snake_case 수신 → camelCase 필드). */
public record ReviewReportRequest(
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<IssueReview> issues,
    String review,
    String status) {

  /** issue 단위 검수 결과. */
  public record IssueReview(
      UUID issueId, String reviewStatus, String adjusterOpinion, String modifiedReason, String excludedReason) {
  }
}
