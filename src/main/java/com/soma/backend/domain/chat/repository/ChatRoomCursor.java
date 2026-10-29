package com.soma.backend.domain.chat.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * GET /chats 목록 keyset 커서 위치(정렬 키 {@code last_message_at DESC NULLS LAST, id DESC}).
 * {@code lastMessageAt}이 null이면 커서가 이미 메시지 없는 방(null 구간) 안에 있다는 뜻이다.
 * 첫 페이지는 커서 자체를 null로 넘긴다.
 */
public record ChatRoomCursor(@Nullable LocalDateTime lastMessageAt, UUID id) {
}
