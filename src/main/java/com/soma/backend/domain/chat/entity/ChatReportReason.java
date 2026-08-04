package com.soma.backend.domain.chat.entity;

import org.springframework.util.StringUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * CHATROOM_REPORTS.reason — 채팅방 신고 사유. DB·앱 모두 enum 이름(대문자)을 문자열로 저장한다.
 * {@code OTHER}는 직접 입력이며 reason_detail이 필수다.
 */
public enum ChatReportReason {
  /** 스팸·광고성 메시지. */
  SPAM,
  /** 욕설·비방·괴롭힘. */
  ABUSE,
  /** 사기·허위 정보. */
  FRAUD,
  /** 개인정보 침해. */
  PRIVACY_VIOLATION,
  /** 기타(reason_detail 필수). */
  OTHER;

  /** 요청 문자열을 사유로 변환한다. 누락이면 MISSING_REQUIRED_FIELD, 미지원 값이면 VALIDATION_ERROR. */
  public static ChatReportReason from(String raw) {
    if (!StringUtils.hasText(raw)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    try {
      return ChatReportReason.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
