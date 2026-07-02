package com.soma.backend.report.domain.model;

/**
 * REPORTS.status 값 객체. 정상 진행: AWAITING_INSPECTION → AWAITING_ADOPTION → COUNSELING → MATCHED.
 * 전이 허용 여부는 {@link Report#applyReviewTransition(ReportStatus)} 참고.
 */
public enum ReportStatus {
  AWAITING_INSPECTION,
  AWAITING_ADOPTION,
  COUNSELING,
  MATCHED
}
