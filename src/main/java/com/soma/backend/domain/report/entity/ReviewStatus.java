package com.soma.backend.domain.report.entity;

/**
 * REPORT_REVIEWS.status 값 객체(제안 = 사정사별 검수 진행 상태).
 * SENT(검수 등록) → COUNSELING(상담 중) → ACCEPTED(사용자 채택) / REJECTED(사용자 거절). ERD 정합(2026-07-09).
 */
public enum ReviewStatus {
  SENT,
  COUNSELING,
  REJECTED,
  ACCEPTED
}
