package com.soma.backend.domain.report.entity;

/** REPORT_REVIEWS.status 값 객체(사정사 검수 진행 상태, ERD 정합으로 ReviewOutcome에서 개명). */
public enum ReviewStatus {
  SENT,
  CONSULTATION,
  NOT_SELECTED,
  CLOSED
}
