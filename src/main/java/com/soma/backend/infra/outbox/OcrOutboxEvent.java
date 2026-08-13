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
 * OCR 트리거 SQS 발행용 트랜잭셔널 아웃박스 이벤트. 도메인 트랜잭션과 같은 트랜잭션 안에서 저장되고,
 * {@code OutboxRelay}가 별도 스케줄로 폴링해 SQS로 발행한다(at-least-once).
 * payload는 호출측(예: OcrJobOutboxPortImpl)이 이미 직렬화한 JSON 문자열을 그대로 저장한다.
 * develop의 {@link OutboxEvent}(회원 탈퇴 Redis 후처리)와 목적·스키마가 달라 별도 엔티티/테이블로 둔다.
 *
 * <p><b>클래스 이름과 테이블 이름이 다른 이유:</b> 브로커가 Kafka→SQS로 바뀌어(#208) {@code Kafka*}
 * 라는 이름이 실제 구현과 어긋나 클래스는 {@code OcrOutbox*}로 정리했지만, 테이블 {@code kafka_outbox_events}
 * (V13)는 그대로 둔다. PostgreSQL의 {@code ALTER TABLE ... RENAME TO}는 대상 스키마의 {@code CREATE}
 * 권한을 요구하는데 운영 DB 유저는 {@code public}에 그 권한이 없어(PG15+ 기본 REVOKE, 이슈 #223) 리네임
 * 마이그레이션이 배포 중 실패하기 때문이다. 테이블 리네임은 권한 정리 후 별도로 다룬다.
 */
@Entity
@Table(name = "kafka_outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OcrOutboxEvent {

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
  private OcrOutboxStatus status;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  private OcrOutboxEvent(String aggregateType, UUID aggregateId, String topic, String messageKey, String payload) {
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.messageKey = messageKey;
    this.payload = payload;
    this.status = OcrOutboxStatus.PENDING;
    this.attempts = 0;
  }

  /** 신규 PENDING 아웃박스 이벤트 생성. 호출자의 트랜잭션 안에서 저장되어야 한다. */
  public static OcrOutboxEvent pending(
      String aggregateType, UUID aggregateId, String topic, String messageKey, String payload) {
    return new OcrOutboxEvent(aggregateType, aggregateId, topic, messageKey, payload);
  }

  /** SQS 발행 성공 처리. */
  public void markSent() {
    this.status = OcrOutboxStatus.SENT;
    this.sentAt = LocalDateTime.now();
  }

  /** 발행 실패 처리 — 시도 횟수를 늘리고, 한도를 넘으면 FAILED로 파킹(재시도 중단, 운영 개입 필요). */
  public void markAttemptFailed(int maxAttempts) {
    this.attempts++;
    if (this.attempts >= maxAttempts) {
      this.status = OcrOutboxStatus.FAILED;
    }
  }
}
