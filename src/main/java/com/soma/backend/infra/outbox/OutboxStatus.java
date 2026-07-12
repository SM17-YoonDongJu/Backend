package com.soma.backend.infra.outbox;

/**
 * 아웃박스 이벤트 처리 상태. DB outbox_events.status(varchar(20))에 {@code name()} 문자열로 저장된다.
 */
public enum OutboxStatus {
  PENDING,
  PROCESSED,
  FAILED
}
