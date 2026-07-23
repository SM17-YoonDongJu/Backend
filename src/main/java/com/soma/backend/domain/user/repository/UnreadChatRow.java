package com.soma.backend.domain.user.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/** 대시보드 대표 안읽음 채팅 1건 projection(상대=사정사). unreadCount는 상관 서브쿼리 값. */
public record UnreadChatRow(
    UUID chatRoomId,
    UUID reportId,
    UUID adjusterId,
    String adjusterNickname,
    String adjusterAvatarUrl,
    String lastMessage,
    LocalDateTime lastMessageAt,
    Long unreadCount) {
}
