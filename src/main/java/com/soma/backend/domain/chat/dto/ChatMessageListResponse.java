package com.soma.backend.domain.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /chats/{id}/messages 커서 페이지네이션 응답(설계서 §4 ②). 최신순 목록 + 다음 커서(opaque) + 다음 페이지 여부.
 */
public record ChatMessageListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatMessageResponse> messages,
    @Schema(nullable = true) String nextCursor,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext) {
}
