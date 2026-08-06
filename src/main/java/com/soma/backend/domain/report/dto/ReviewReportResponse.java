package com.soma.backend.domain.report.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API#4 응답.
 *
 * <p>{@code sentAt}은 검수 반영(전송) 시각으로 {@code REPORT_REVIEWS.created_at}(최초 제출 시각)에서
 * 내려준다. 검수 반영은 멱등 upsert라 여러 번 호출될 수 있으나 화면 문구가 "전송 시각"이므로 최초 제출
 * 시각을 노출한다(FE #119).
 */
public record ReviewReportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportReviewId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reviewStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime sentAt) {
}
