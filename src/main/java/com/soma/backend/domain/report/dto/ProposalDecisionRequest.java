package com.soma.backend.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PATCH /reports/{reportId}/proposals/{proposalId} 요청(design.md §6).
 * status = COUNSELING(상담 시작 — 채팅방 개설) | ACCEPTED(채택 — 리포트 종결) | REJECTED(거절).
 */
public record ProposalDecisionRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "COUNSELING(상담 시작·채팅방 개설) / ACCEPTED(채택) / REJECTED(거절)만 허용")
    String status) {
}
