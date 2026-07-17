package com.soma.backend.domain.adjuster.entity;

/**
 * ADJUSTER_APPLICATIONS.status — 자격 신청 심사 상태.
 * PENDING(심사 중) → APPROVED(승인) / REJECTED(반려). 승인·반려 전이는 관리자 승인 API 소관이다.
 */
public enum ApplicationStatus {
  PENDING,
  APPROVED,
  REJECTED
}
