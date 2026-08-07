package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** POST /chats/{id}/read 응답(설계서 §4 ⑥). 갱신된 내 읽음 커서 시각을 반환한다. */
public record ReadResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatRoomId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime readAt) {
}
