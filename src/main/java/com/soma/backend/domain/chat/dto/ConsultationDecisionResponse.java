package com.soma.backend.domain.chat.dto;

import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/**
 * PATCH /chats/{id}/accept·reject 응답(설계서 §4 ④·⑤). 결정 후 방 생명주기·제안 상태·리포트 상태를 함께 반환한다.
 */
public record ConsultationDecisionResponse(
    UUID chatRoomId,
    ChatRoomStatus chatRoomStatus,
    ReviewStatus reviewStatus,
    UUID reportId,
    ReportStatus reportStatus) {
}
