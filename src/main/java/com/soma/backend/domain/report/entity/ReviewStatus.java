package com.soma.backend.domain.report.entity;

/** REPORT_REVIEWS.status 값 객체(사정사 검수·제안 상태, ERD 2026-07 정합). */
public enum ReviewStatus {
  SENT,
  COUNSELING,
  REJECTED,
  ACCEPTED
}
