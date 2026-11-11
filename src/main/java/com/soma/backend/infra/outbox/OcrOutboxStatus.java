package com.soma.backend.infra.outbox;

/** OCR 아웃박스 이벤트 발행 상태(kafka_outbox_events.status — 테이블명은 OcrOutboxEvent javadoc 참조). */
public enum OcrOutboxStatus {
  PENDING,
  SENT,
  FAILED
}
