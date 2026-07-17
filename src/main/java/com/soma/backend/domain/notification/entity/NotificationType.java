package com.soma.backend.domain.notification.entity;

/**
 * 인앱 알림 유형. DB notifications.type(varchar(30))에 {@code name()} 문자열로 저장된다.
 *
 * <p>고객계와 사정사계 이벤트를 함께 담는다. 알 수 없는 값 대비 저장은 항상 name 문자열이며,
 * 값 정합(FE·FastAPI producer 계약 확정)은 별도 티켓 범위라 여기서는 알려진 값만 열거한다.
 */
public enum NotificationType {

  // 고객계
  REVIEW_COMPLETE,
  RECEIVED_PROPOSAL,
  CONSULT_ACCEPTED,
  ANALYSIS_COMPLETE,
  IDENTITY_VERIFIED,
  CHAT_MESSAGE,
  SETTLEMENT_NOTICE,

  // 사정사계
  NEW_REVIEW_REQUEST,
  CONSULT_MESSAGE
}
