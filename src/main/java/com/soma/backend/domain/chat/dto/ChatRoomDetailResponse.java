package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /chats/{chatRoomId} 단건 방 상세(딥링크·새로고침 시 채팅 헤더용). {@code GET /chats} 목록 항목과 동일한
 * 필드에 {@code createdAt}(방 생성 시각)을 더한 형태다. 필드명은 프론트 계약에 맞춘다(§4). {@code counterpart}는
 * {@link ChatRoomSummaryResponse.Counterpart}를 재사용한다.
 */
public record ChatRoomDetailResponse(
    UUID chatRoomId,
    UUID reportId,
    UUID proposalId,
    ChatRoomStatus roomStatus,
    ReviewStatus matchStatus,
    String caseNo,
    AccidentType reportTypeLabel,
    ChatRoomSummaryResponse.Counterpart counterpart,
    String lastMessage,
    LocalDateTime lastMessageAt,
    long unreadCount,
    LocalDateTime createdAt) {
}
