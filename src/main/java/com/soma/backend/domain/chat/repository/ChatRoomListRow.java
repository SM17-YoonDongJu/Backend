package com.soma.backend.domain.chat.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /chats 목록 QueryDSL projection row(설계서 §4 ①). 상대방 이름·아바타 결정을 위해 양쪽 계정 정보를 함께 담는다.
 * {@code reviewStatus}·{@code caseNo}·{@code accidentType}은 report(_reviews) LEFT JOIN 결과(검색 방은 null),
 * {@code unreadCount}는 상관 서브쿼리 값이다.
 */
public record ChatRoomListRow(
    UUID chatRoomId,
    UUID reportId,
    UUID reportReviewId,
    ChatRoomStatus status,
    ReviewStatus reviewStatus,
    String caseNo,
    AccidentType accidentType,
    UUID userId,
    UUID adjusterId,
    String userNickname,
    String adjusterNickname,
    String userAvatarUrl,
    String adjusterAvatarUrl,
    String lastMessage,
    LocalDateTime lastMessageAt,
    Long unreadCount,
    LocalDateTime createdAt) {
}
