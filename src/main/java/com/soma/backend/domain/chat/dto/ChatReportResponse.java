package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatReportReason;

/**
 * POST /chats/{chatRoomId}/report 응답. 전역 snake_case 설정으로 chat_report_id·created_at으로
 * 직렬화된다. reporterId·reportedId·reasonDetail은 FE 계약상 노출하지 않는다.
 */
public record ChatReportResponse(
    UUID chatReportId,
    UUID chatRoomId,
    ChatReportReason reason,
    LocalDateTime createdAt) {
}
