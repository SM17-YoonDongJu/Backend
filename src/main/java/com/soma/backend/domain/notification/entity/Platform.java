package com.soma.backend.domain.notification.entity;

/**
 * 디바이스 푸시 플랫폼 구분(Value Object). 식별자 없는 불변 값이다.
 * device_tokens.platform(varchar(10))에 {@code @Enumerated(STRING)}으로 매핑된다(최장 "ANDROID"=7자).
 */
public enum Platform {
  ANDROID,
  IOS,
  WEB
}
