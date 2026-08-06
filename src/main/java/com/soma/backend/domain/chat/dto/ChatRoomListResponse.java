package com.soma.backend.domain.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET /chats 응답 래퍼(설계서 §4 ①: {@code { rooms: [...] }}). */
public record ChatRoomListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatRoomSummaryResponse> rooms) {
}
