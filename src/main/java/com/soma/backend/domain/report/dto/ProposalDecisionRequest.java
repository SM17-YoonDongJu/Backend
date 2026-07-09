package com.soma.backend.domain.report.dto;

/** PATCH /reports/{reportId}/proposals/{proposalId} 요청(design.md §6). status = ACCEPTED | REJECTED. */
public record ProposalDecisionRequest(String status) {
}
