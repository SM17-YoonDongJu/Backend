package com.soma.backend.domain.user.entity;

/**
 * 사용자 권한. DB users.role(varchar(30))에 {@code name()} 문자열로 저장된다.
 */
public enum Role {
  USER,
  CERTIFICATED_ADJUSTER,
  UNCERTIFICATED_ADJUSTER,
  ADMIN
}
