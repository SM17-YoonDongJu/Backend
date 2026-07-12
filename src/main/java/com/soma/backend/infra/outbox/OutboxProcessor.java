package com.soma.backend.infra.outbox;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.infra.redis.RefreshTokenRepository;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

/**
 * 아웃박스 poller. 커밋된 PENDING 이벤트를 주기적으로 집어 외부 부수효과를 처리하고, 실패 시 지수 백오프로
 * 재시도한다. 처리(Redis 연산)는 모두 멱등이라 at-least-once로 안전하다. {@code FOR UPDATE SKIP LOCKED}로
 * 다중 인스턴스 중복 처리를 막는다. {@code app.outbox.enabled=false}면 비활성화된다(테스트 등).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxProcessor {

  private final OutboxEventRepository outboxEventRepository;
  private final JsonMapper jsonMapper;
  private final RefreshTokenRepository refreshTokenRepository;
  private final TokenBlacklistRepository tokenBlacklistRepository;

  @Value("${app.outbox.batch-size:50}")
  private int batchSize;

  @Value("${app.outbox.backoff-seconds:30}")
  private long backoffSeconds;

  /**
   * 처리 대상 이벤트를 배치로 잠가 처리한다. 한 트랜잭션에서 배치를 잠그고(SKIP LOCKED) 각 이벤트를
   * 개별적으로 성공/실패 처리하며, 상태 변경은 커밋 시 dirty checking으로 반영된다.
   */
  @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
  @Transactional
  public void poll() {
    LocalDateTime now = LocalDateTime.now();
    List<OutboxEvent> batch = outboxEventRepository.lockPendingBatch(now, batchSize);
    for (OutboxEvent event : batch) {
      process(event, now);
    }
  }

  private void process(OutboxEvent event, LocalDateTime now) {
    try {
      handle(event);
      event.markProcessed(now);
    } catch (RuntimeException ex) {
      long backoff = backoffSeconds * (event.getRetryCount() + 1L);
      event.markFailed(now.plusSeconds(backoff));
      log.warn("아웃박스 이벤트 처리 실패, 재시도 예약 (id={}, type={}, retry={}): {}",
          event.getId(), event.getEventType(), event.getRetryCount(), ex.getMessage());
    }
  }

  private void handle(OutboxEvent event) {
    switch (event.getEventType()) {
      case OutboxEventPublisher.EVENT_AUTH_CLEANUP -> handleAuthCleanup(event.getPayload());
      default -> throw new IllegalStateException("알 수 없는 아웃박스 이벤트 타입: " + event.getEventType());
    }
  }

  private void handleAuthCleanup(String payloadJson) {
    AuthCleanupPayload payload = jsonMapper.readValue(payloadJson, AuthCleanupPayload.class);
    refreshTokenRepository.delete(payload.userId());
    tokenBlacklistRepository.blacklist(payload.userId());
  }
}
