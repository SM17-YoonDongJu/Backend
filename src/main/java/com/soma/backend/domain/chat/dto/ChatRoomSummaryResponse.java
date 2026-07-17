package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /chats 채팅방 목록 항목(설계서 §4 ①). 필드명은 프론트 계약에 맞춘다 — {@code roomStatus}는 방
 * 생명주기(ACTIVE/CLOSED, 값은 백엔드 모델 유지), {@code matchStatus}는 제안 결정 상태(REPORT_REVIEWS.status,
 * 파이프라인 방만·검색 방은 null), {@code proposalId}는 제안 ID(REPORT_REVIEWS.id), {@code reportTypeLabel}은
 * 사고 유형(REPORTS.accident_type). {@code counterpart}는 역할 중립 상대(고객↔사정사) 표시 정보다.
 */
public record ChatRoomSummaryResponse(
    UUID chatRoomId,
    UUID reportId,
    UUID proposalId,
    ChatRoomStatus roomStatus,
    ReviewStatus matchStatus,
    String caseNo,
    AccidentType reportTypeLabel,
    Counterpart counterpart,
    String lastMessage,
    LocalDateTime lastMessageAt,
    long unreadCount) {

  /** 상대방(고객 또는 사정사) 식별·표시 정보. */
  public record Counterpart(UUID userId, String name, String avatarUrl) {
  }
}
