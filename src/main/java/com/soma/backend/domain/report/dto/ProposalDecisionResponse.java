package com.soma.backend.domain.report.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/** PATCH /reports/{reportId}/proposals/{proposalId} 응답(design.md §6). */
public record ProposalDecisionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID adjusterId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReportStatus reportStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewStatus reviewStatus) {
}
