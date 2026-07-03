package com.soma.backend.domain.report.dto;

import java.util.UUID;

/** API#4 응답. */
public record ReviewReportResponse(UUID reportId, String status, UUID reportReviewId, String outcome) {
}
