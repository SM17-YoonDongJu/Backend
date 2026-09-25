package com.soma.backend.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * PATCH /reports/{reportId}/proposals/{proposalId} 요청(design.md §6).
 * status = ACCEPTED(상담 수락 — 채팅방 개설) | REJECTED(현재 아무 동작 없음).
 * 최종 채택·거절은 채팅방 화면(PATCH /chats/{chatRoomId}/accept·reject) 전용이다.
 */
public record ProposalDecisionRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "ACCEPTED(상담 수락 — 채팅방 개설) 또는 REJECTED(현재 아무 동작 없음)만 허용. "
            + "ACCEPTED 요청의 응답 review_status는 ACCEPTED가 아니라 COUNSELING이다"
            + "(제안 최종 채택은 PATCH /chats/{chatRoomId}/accept 전용)")
    String status) {

  /**
   * status를 "상담 수락 여부"로 해석한다. ACCEPTED=상담 수락(채팅방 개설)→true, REJECTED=현재 무동작→false.
   * 빈 값이면 400 MISSING_REQUIRED_FIELD, 그 외 값이면 400 VALIDATION_ERROR. 요청 문자열 해석은 HTTP 계약
   * 관심사라 서비스가 아니라 이 요청 DTO가 소유한다(거절 최종 처리는 PATCH /chats/{chatRoomId}/reject 전용).
   */
  public boolean startsCounseling() {
    if (status == null || status.isBlank()) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    if ("ACCEPTED".equals(status)) {
      return true;
    }
    if ("REJECTED".equals(status)) {
      return false;
    }
    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
  }
}
