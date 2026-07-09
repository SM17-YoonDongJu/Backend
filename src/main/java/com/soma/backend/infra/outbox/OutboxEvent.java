package com.soma.backend.infra.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트랜잭셔널 아웃박스 이벤트(V3__outbox.sql). 도메인 트랜잭션과 같은 트랜잭션 안에서 저장되고,
 * {@code OutboxRelay}가 별도 스케줄로 폴링해 Kafka로 발행한다(at-least-once 전달 보장).
 * payload는 호출측(예: OcrJobOutboxPortImpl)이 이미 직렬화한 JSON 문자열을 그대로 저장한다.
 * {@code @JdbcTypeCode(SqlTypes.JSON)}을 String 필드에 붙이면 Hibernate가 재직렬화 없이
 * 원문 그대로 jsonb 컬럼에 바인딩한다(AbstractJsonFormatMapper가 String 타입은 그대로 통과시킴) —
 * Jackson3/Hibernate7 FormatMapper의 다형성 처리 리스크(design.md §3.2)를 이 엔티티는 겪지 않는다.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "aggregate_type", nullable = false, length = 50)
  private String aggregateType;

  @Column(name = "aggregate_id")
  private UUID aggregateId;

  @Column(name = "topic", nullable = false, length = 100)
  private String topic;

  @Column(name = "message_key", nullable = false, length = 100)
  private String messageKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  private OutboxEvent(String aggregateType, UUID aggregateId, String topic, String messageKey, String payload) {
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.messageKey = messageKey;
    this.payload = payload;
    this.status = OutboxStatus.PENDING;
    this.attempts = 0;
  }

  /** 신규 PENDING 아웃박스 이벤트 생성. 호출자의 트랜잭션 안에서 저장되어야 한다. */
  public static OutboxEvent pending(
      String aggregateType, UUID aggregateId, String topic, String messageKey, String payload) {
    return new OutboxEvent(aggregateType, aggregateId, topic, messageKey, payload);
  }

  /** Kafka 발행 성공 처리. */
  public void markSent() {
    this.status = OutboxStatus.SENT;
    this.sentAt = LocalDateTime.now();
  }

  /** 발행 실패 처리 — 시도 횟수를 늘리고, 한도를 넘으면 FAILED로 파킹(재시도 중단, 운영 개입 필요). */
  public void markAttemptFailed(int maxAttempts) {
    this.attempts++;
    if (this.attempts >= maxAttempts) {
      this.status = OutboxStatus.FAILED;
    }
  }
}
