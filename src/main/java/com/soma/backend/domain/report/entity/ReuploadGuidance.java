package com.soma.backend.domain.report.entity;

/**
 * 분석 실패 시 사용자에게 제시할 재업로드 안내(design.md §6-2). 실패 사유({@link AnalysisFailureReason})가
 * 소유하는 부가 규칙이라 enum 상수마다 하나씩 고정된다.
 */
public enum ReuploadGuidance {

  /** 재업로드로 해결 가능 — 사용자에게 다시 올려달라고 안내한다(파일 손상·판독 불가). */
  RECOMMENDED,

  /** 재업로드해도 결과가 같다 — 안내하면 안 된다(마스킹 잔류·내부 계약 결함). */
  NOT_SUPPORTED,

  /** 판단 보류 — 확인 중임을 알리고 사용자 액션은 요구하지 않는다. */
  HOLD
}
