package com.soma.backend.infra.outbox;

/** 아웃박스 이벤트 발행 상태(outbox_events.status, V3__outbox.sql). */
public enum OutboxStatus {
  PENDING,
  SENT,
  FAILED
}
