package com.soma.backend.report.application.dto;

import java.util.List;
import java.util.UUID;

/** API#4 검수 반영 유스케이스 입력. */
public record ReviewReportCommand(
    UUID adjusterId,
    UUID reportId,
    List<String> applicableGuarantees,
    List<String> omittedSpecialContract,
    List<IssueReviewCommand> issues,
    String review,
    String status) {

  /** issue 단위 검수 결과. */
  public record IssueReviewCommand(
      UUID issueId, String reviewStatus, String adjusterOpinion, String modifiedReason, String excludedReason) {
  }
}
