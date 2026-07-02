package com.soma.backend.report.application.dto;

/** API#1 응답 결과. specialty_match_count는 리더 확정으로 제거됨. */
public record PendingReviewSummaryResult(long pendingCount, long dueSoonCount) {
}
