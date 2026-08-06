package com.soma.backend.domain.notification.dto;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 알림 설정 부분 수정 요청(PATCH /users/me/notification-settings).
 * 전달한 토글만 변경하고 {@code null}(미전달)은 그대로 둔다. snake_case → camel 매핑은 Jackson 전역 설정.
 */
public record NotificationSettingUpdateRequest(
    @Nullable @Schema(nullable = true) Boolean newReviewRequest,
    @Nullable @Schema(nullable = true) Boolean consultMessage,
    @Nullable @Schema(nullable = true) Boolean settlementNotice,
    @Nullable @Schema(nullable = true) Boolean reviewDeadlineSoon,
    @Nullable @Schema(nullable = true) Boolean reviewComplete,
    @Nullable @Schema(nullable = true) Boolean receivedProposal,
    @Nullable @Schema(nullable = true) Boolean consultAccepted,
    @Nullable @Schema(nullable = true) Boolean analysisComplete,
    @Nullable @Schema(nullable = true) Boolean identityVerified,
    @Nullable @Schema(nullable = true) Boolean marketing) {
}
