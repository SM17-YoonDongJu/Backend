package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatRoomId,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 리포트가 없어 null") UUID reportId,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 제안이 없어 null") UUID proposalId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatRoomStatus roomStatus,
    @Schema(nullable = true, description = "검색으로 개설된 방은 제안이 없어 null") ReviewStatus matchStatus,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 리포트가 없어 null") String caseNo,
    @Schema(nullable = true, description = "검색으로 개설된 방은 연결된 리포트가 없어 null") AccidentType reportTypeLabel,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Counterpart counterpart,
    @Schema(nullable = true, description = "아직 메시지가 없으면 null") String lastMessage,
    @Schema(nullable = true, description = "아직 메시지가 없으면 null") LocalDateTime lastMessageAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unreadCount) {

  /** 상대방(고객 또는 사정사) 식별·표시 정보. */
  public record Counterpart(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
      @Schema(nullable = true, description = "상대 계정에 프로필 이미지가 없으면 null") String avatarUrl) {
  }
}
