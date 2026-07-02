package com.soma.backend.report.application.dto;

import java.util.UUID;

/** API#4 검수 반영 결과. */
public record ReviewReportResult(UUID reportId, String status, UUID reportReviewId, String outcome) {
}
