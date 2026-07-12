package com.soma.backend.infra.outbox;

/** Kafka 아웃박스 이벤트 발행 상태(kafka_outbox_events.status, V13__kafka_outbox.sql). */
public enum KafkaOutboxStatus {
  PENDING,
  SENT,
  FAILED
}
