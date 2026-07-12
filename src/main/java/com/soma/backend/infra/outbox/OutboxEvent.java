package com.soma.backend.infra.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

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
 * 트랜잭션 아웃박스 레코드. 도메인 트랜잭션과 같은 커밋으로 저장돼, 커밋 이후 {@link OutboxProcessor}가
 * 외부 부수효과(Redis 등)를 재시도하며 처리하도록 한다(dual-write 불일치 제거).
 *
 * <p>부수효과가 멱등이면 at-least-once 처리가 안전하다. 실패 시 {@link #markFailed}로 백오프 후 재시도하며,
 * 최대 재시도를 넘기면 {@code FAILED}(dead-letter)로 남겨 운영이 확인하게 한다.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

  private static final int MAX_RETRY = 10;

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "aggregate_type", nullable = false, length = 50)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @Column(name = "payload", nullable = false, columnDefinition = "text")
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "next_attempt_at", nullable = false)
  private LocalDateTime nextAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "processed_at")
  private LocalDateTime processedAt;

  /**
   * PENDING 상태로 즉시 처리 대상(nextAttemptAt=now)인 이벤트를 만든다.
   */
  public static OutboxEvent of(
      String aggregateType, UUID aggregateId, String eventType, String payload, LocalDateTime now) {
    OutboxEvent event = new OutboxEvent();
    event.aggregateType = aggregateType;
    event.aggregateId = aggregateId;
    event.eventType = eventType;
    event.payload = payload;
    event.status = OutboxStatus.PENDING;
    event.retryCount = 0;
    event.nextAttemptAt = now;
    event.createdAt = now;
    return event;
  }

  /**
   * 처리 성공: PROCESSED로 종료한다.
   */
  public void markProcessed(LocalDateTime now) {
    this.status = OutboxStatus.PROCESSED;
    this.processedAt = now;
  }

  /**
   * 처리 실패: 재시도 횟수를 늘리고 백오프 시각으로 미룬다. 최대 재시도를 넘기면 FAILED(dead-letter)로
   * 표시해 poller가 더는 집지 않게 한다.
   */
  public void markFailed(LocalDateTime nextAttemptAt) {
    this.retryCount++;
    if (this.retryCount >= MAX_RETRY) {
      this.status = OutboxStatus.FAILED;
    } else {
      this.nextAttemptAt = nextAttemptAt;
    }
  }
}
