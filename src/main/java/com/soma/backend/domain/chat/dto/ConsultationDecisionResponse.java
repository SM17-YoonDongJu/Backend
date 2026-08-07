package com.soma.backend.domain.chat.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * PATCH /chats/{id}/accept·reject 응답(설계서 §4 ④·⑤). 결정 후 방 생명주기·제안 상태·리포트 상태를 함께 반환한다.
 */
public record ConsultationDecisionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatRoomId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatRoomStatus chatRoomStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewStatus reviewStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReportStatus reportStatus) {
}
