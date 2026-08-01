package com.soma.backend.domain.notification.dto;

import org.jspecify.annotations.Nullable;

/**
 * 알림 설정 부분 수정 요청(PATCH /users/me/notification-settings).
 * 전달한 토글만 변경하고 {@code null}(미전달)은 그대로 둔다. snake_case → camel 매핑은 Jackson 전역 설정.
 */
public record NotificationSettingUpdateRequest(
    @Nullable Boolean newReviewRequest,
    @Nullable Boolean consultMessage,
    @Nullable Boolean settlementNotice,
    @Nullable Boolean reviewDeadlineSoon,
    @Nullable Boolean reviewComplete,
    @Nullable Boolean receivedProposal,
    @Nullable Boolean consultAccepted,
    @Nullable Boolean analysisComplete,
    @Nullable Boolean identityVerified,
    @Nullable Boolean marketing) {
}
