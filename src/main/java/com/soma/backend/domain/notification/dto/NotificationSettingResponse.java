package com.soma.backend.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.notification.entity.NotificationSetting;

/**
 * 알림 설정 응답(GET·PATCH /users/me/notification-settings).
 * Jackson 전역 설정으로 snake_case 직렬화된다(new_review_request·review_deadline_soon 등).
 */
public record NotificationSettingResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean newReviewRequest,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean consultMessage,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean settlementNotice,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reviewDeadlineSoon,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reviewComplete,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean receivedProposal,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean consultAccepted,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean analysisComplete,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean identityVerified,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean marketing) {

  public static NotificationSettingResponse from(NotificationSetting setting) {
    return new NotificationSettingResponse(
        setting.isNewReviewRequest(),
        setting.isConsultMessage(),
        setting.isSettlementNotice(),
        setting.isReviewDeadlineSoon(),
        setting.isReviewComplete(),
        setting.isReceivedProposal(),
        setting.isConsultAccepted(),
        setting.isAnalysisComplete(),
        setting.isIdentityVerified(),
        setting.isMarketing());
  }
}
