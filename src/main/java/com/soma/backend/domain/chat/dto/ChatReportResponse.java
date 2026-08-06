package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.chat.entity.ChatReportReason;

/**
 * POST /chats/{chatRoomId}/report 응답. 전역 snake_case 설정으로 chat_report_id·created_at으로
 * 직렬화된다. reporterId·reportedId·reasonDetail은 FE 계약상 노출하지 않는다.
 */
public record ChatReportResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatReportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatRoomId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatReportReason reason,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {
}
