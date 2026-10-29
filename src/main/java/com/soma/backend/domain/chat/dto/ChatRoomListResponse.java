package com.soma.backend.domain.chat.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /chats 커서 페이지네이션 응답(설계서 §4 ①). 최근 활동순 방 목록 + 다음 커서(opaque) + 다음 페이지 여부.
 * {@code next_cursor}는 {@code has_next=false}면 null이다.
 */
public record ChatRoomListResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatRoomSummaryResponse> rooms,
    @Schema(nullable = true, description = "다음 페이지 조회용 opaque 커서. has_next=false면 null") String nextCursor,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext) {
}
