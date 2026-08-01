package com.soma.backend.domain.adjuster.entity;

/** 자격 신청 문서 심사 상태(API 명세 documents[].status). 반려 시 RESUBMIT_REQUIRED로 재제출을 요구한다. */
public enum DocumentStatus {
  PENDING,
  APPROVED,
  RESUBMIT_REQUIRED
}
