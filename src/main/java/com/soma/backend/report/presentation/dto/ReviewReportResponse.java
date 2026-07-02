package com.soma.backend.report.presentation.dto;

import java.util.UUID;

import com.soma.backend.report.application.dto.ReviewReportResult;

/** API#4 응답. */
public record ReviewReportResponse(UUID reportId, String status, UUID reportReviewId, String outcome) {

  public static ReviewReportResponse from(ReviewReportResult result) {
    return new ReviewReportResponse(result.reportId(), result.status(), result.reportReviewId(), result.outcome());
  }
}
