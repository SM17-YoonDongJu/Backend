package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /chats 채팅방 목록 항목(설계서 §4 ①). {@code status}는 방 생명주기(ACTIVE/CLOSED),
 * {@code reviewStatus}는 제안 결정 상태(파이프라인 방만·검색 방은 null). {@code caseNo}·{@code accidentType}은
 * 연결된 리포트 정보(검색 방은 null). {@code counterpart.avatarUrl}은 상대방 아바타(users.avatar_url).
 */
public record ChatRoomSummaryResponse(
    UUID chatRoomId,
    UUID reportId,
    UUID reportReviewId,
    ChatRoomStatus status,
    ReviewStatus reviewStatus,
    String caseNo,
    AccidentType accidentType,
    Counterpart counterpart,
    String lastMessage,
    LocalDateTime lastMessageAt,
    long unreadCount) {

  /** 상대방(고객 또는 사정사) 식별·표시 정보. */
  public record Counterpart(UUID userId, String name, String avatarUrl) {
  }
}
