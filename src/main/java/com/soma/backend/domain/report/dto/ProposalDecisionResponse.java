package com.soma.backend.domain.report.dto;

import java.util.UUID;

import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/** PATCH /reports/{reportId}/proposals/{proposalId} 응답(design.md §6). */
public record ProposalDecisionResponse(
    UUID reportId,
    UUID proposalId,
    UUID adjusterId,
    ReportStatus reportStatus,
    ReviewStatus reviewStatus) {
}
