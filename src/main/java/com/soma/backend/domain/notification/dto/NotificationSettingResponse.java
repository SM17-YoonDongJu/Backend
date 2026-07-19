package com.soma.backend.domain.notification.dto;

import com.soma.backend.domain.notification.entity.NotificationSetting;

/**
 * 알림 설정 응답(GET·PATCH /users/me/notification-settings).
 * Jackson 전역 설정으로 snake_case 직렬화된다(new_review_request·review_deadline_soon 등).
 */
public record NotificationSettingResponse(
    boolean newReviewRequest,
    boolean consultMessage,
    boolean settlementNotice,
    boolean reviewDeadlineSoon,
    boolean reviewComplete,
    boolean receivedProposal,
    boolean consultAccepted,
    boolean analysisComplete,
    boolean identityVerified,
    boolean marketing) {

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
