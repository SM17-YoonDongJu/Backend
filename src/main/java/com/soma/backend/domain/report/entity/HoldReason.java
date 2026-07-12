package com.soma.backend.domain.report.entity;

/**
 * REPORT_HOLDS.reason — 사정사가 사건을 보류한 사유(검수 대기 보류 모달).
 * DB·앱 모두 enum 이름(대문자)을 문자열로 저장한다. {@code OTHER}는 직접 입력이며 reason_detail이 필수다.
 */
public enum HoldReason {
  /** 추가 자료가 필요해요. */
  NEED_MORE_DOCUMENTS,
  /** 전문분야와 달라요. */
  OUT_OF_SPECIALTY,
  /** 일정상 어려워요. */
  SCHEDULE_CONFLICT,
  /** 직접 입력(reason_detail 필수). */
  OTHER
}
