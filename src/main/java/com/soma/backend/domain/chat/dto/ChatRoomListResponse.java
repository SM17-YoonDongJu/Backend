package com.soma.backend.domain.chat.dto;

import java.util.List;

/** GET /chats 응답 래퍼(설계서 §4 ①: {@code { rooms: [...] }}). */
public record ChatRoomListResponse(List<ChatRoomSummaryResponse> rooms) {
}
