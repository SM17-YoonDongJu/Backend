package com.soma.backend.domain.chat.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /chats 목록 QueryDSL projection row(설계서 §4 ①). 상대방 이름 결정을 위해 양쪽 계정 닉네임을 함께 담고,
 * {@code reviewStatus}는 report_reviews LEFT JOIN 결과(검색 방은 null), {@code unreadCount}는 상관 서브쿼리 값이다.
 */
public record ChatRoomListRow(
    UUID chatRoomId,
    UUID reportId,
    UUID reportReviewId,
    ChatRoomStatus status,
    ReviewStatus reviewStatus,
    UUID userId,
    UUID adjusterId,
    String userNickname,
    String adjusterNickname,
    String lastMessage,
    LocalDateTime lastMessageAt,
    Long unreadCount) {
}
