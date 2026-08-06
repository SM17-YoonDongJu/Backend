package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * GET /chats/{chatRoomId} 단건 방 상세(딥링크·새로고침 시 채팅 헤더용). {@code GET /chats} 목록 항목과 동일한
 * 필드에 {@code createdAt}(방 생성 시각)을 더한 형태다. 필드명은 프론트 계약에 맞춘다(§4). {@code counterpart}는
 * {@link ChatRoomSummaryResponse.Counterpart}를 재사용한다.
 */
public record ChatRoomDetailResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatRoomId,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 리포트가 없어 null") UUID reportId,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 제안이 없어 null") UUID proposalId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatRoomStatus roomStatus,
    @Schema(nullable = true, description = "검색으로 개설된 방은 제안이 없어 null") ReviewStatus matchStatus,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 리포트가 없어 null") String caseNo,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 리포트가 없어 null") AccidentType reportTypeLabel,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatRoomSummaryResponse.Counterpart counterpart,
    @Schema(nullable = true, description = "아직 메시지가 없으면 null") String lastMessage,
    @Schema(nullable = true, description = "아직 메시지가 없으면 null") LocalDateTime lastMessageAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unreadCount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {
}
