package com.soma.backend.report.presentation.dto;

import com.soma.backend.report.application.dto.PendingReviewSummaryResult;

/** API#1 응답. */
public record PendingReviewSummaryResponse(long pendingCount, long dueSoonCount) {

  public static PendingReviewSummaryResponse from(PendingReviewSummaryResult result) {
    return new PendingReviewSummaryResponse(result.pendingCount(), result.dueSoonCount());
  }
}
