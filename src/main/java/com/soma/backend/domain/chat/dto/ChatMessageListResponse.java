package com.soma.backend.domain.chat.dto;

import java.util.List;

/**
 * GET /chats/{id}/messages 커서 페이지네이션 응답(설계서 §4 ②). 최신순 목록 + 다음 커서(opaque) + 다음 페이지 여부.
 */
public record ChatMessageListResponse(
    List<ChatMessageResponse> messages,
    String nextCursor,
    boolean hasNext) {
}
