package com.soma.backend.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** PATCH /reports/{reportId}/proposals/{proposalId} 요청(design.md §6). status = ACCEPTED | REJECTED. */
public record ProposalDecisionRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ACCEPTED 또는 REJECTED만 허용") String status) {
}
