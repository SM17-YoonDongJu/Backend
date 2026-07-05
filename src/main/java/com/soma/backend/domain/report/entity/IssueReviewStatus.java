package com.soma.backend.domain.report.entity;

/** REPORT_REVIEW_ISSUES.review_status 값 객체. ADDED는 사정사가 신규로 추가한 쟁점(report_issue_id=null). */
public enum IssueReviewStatus {
  ACCEPTED,
  MODIFIED,
  EXCLUDED,
  ADDED
}
