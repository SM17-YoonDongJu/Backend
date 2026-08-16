package com.soma.backend.domain.notification.entity;

/**
 * 인앱 알림 유형. DB notifications.type(varchar(30))에 {@code name()} 문자열로 저장된다.
 *
 * <p>고객계와 사정사계 이벤트를 함께 담으며, 값은 설정 토글 어근(NOTIFICATION_SETTINGS)과
 * FE 요청(#97·type enum 정합)에 정합시켰다(REVIEW_COMPLETE↔review_complete 등). 저장은 항상
 * name 문자열이라 값 추가·변경은 마이그레이션 없이 이 enum만 고치면 된다. type↔설정 키 매핑과
 * producer 발송 억제(설정 off면 미발송)는 알림 설정 도메인·producer 배선에서 다룬다.
 */
public enum NotificationType {

  // 고객계
  REVIEW_COMPLETE,
  RECEIVED_PROPOSAL,
  CONSULT_ACCEPTED,
  ANALYSIS_COMPLETE,
  /** 분석(OCR·AI) 확정 실패 통지. 시스템 실패 알림이라 수신 토글 대상이 아니다(항상 발송). */
  ANALYSIS_FAILED,
  /** AI 입력 가드레일 차단 통지. ANALYSIS_FAILED와 마찬가지로 시스템 알림이라 항상 발송된다. */
  REPORT_BLOCKED,
  /** OCR 품질 미달 통지. 사용자 액션(재업로드)이 필요한 시스템 통지라 수신 토글 대상이 아니다(항상 발송). */
  REPORT_NEEDS_REUPLOAD,
  IDENTITY_VERIFIED,
  CHAT_MESSAGE,
  SETTLEMENT_NOTICE,
  PROPOSAL_CLOSED,

  // 사정사계
  NEW_REVIEW_REQUEST,
  REVIEW_DEADLINE_SOON,
  CONSULT_REQUESTED
}
