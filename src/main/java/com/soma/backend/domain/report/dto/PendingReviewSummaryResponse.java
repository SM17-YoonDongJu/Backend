package com.soma.backend.domain.report.dto;

/** API#1 응답. */
public record PendingReviewSummaryResponse(long pendingCount, long dueSoonCount) {
}
