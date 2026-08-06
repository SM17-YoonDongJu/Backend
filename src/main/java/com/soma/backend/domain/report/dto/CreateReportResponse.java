package com.soma.backend.domain.report.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.ReportStatus;

/** POST /reports 202 응답(design.md §6). */
public record CreateReportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReportStatus status) {
}
