package com.soma.backend.domain.user.entity;

/**
 * 사용자 계정 상태. DB users.status(varchar(20))에 {@code name()} 문자열로 저장된다.
 */
public enum UserStatus {
  ACTIVE,
  WITHDRAWN
}
