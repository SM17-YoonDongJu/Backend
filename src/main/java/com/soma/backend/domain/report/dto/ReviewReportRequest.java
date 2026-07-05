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
    String review,
    String status) {

  /**
   * issue 단위 검수 결과. issueId가 null이면 ADDED(사정사 신규 쟁점)이며 title·description이 필수다.
   */
  public record IssueReview(
      UUID issueId, String reviewStatus, String title, String description,
      String adjusterOpinion, String modifiedReason, String excludedReason) {
  }
}
