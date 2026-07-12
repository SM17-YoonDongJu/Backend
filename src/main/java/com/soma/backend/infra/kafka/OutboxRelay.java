package com.soma.backend.infra.kafka;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.infra.outbox.OutboxEvent;
import com.soma.backend.infra.outbox.OutboxRepository;

/**
 * 아웃박스 릴레이. PENDING 이벤트를 고정 지연(fixedDelay) 폴링으로 조회해 Kafka로 발행한다(design.md §4).
 * {@code FOR UPDATE SKIP LOCKED}로 조회한 행은 같은 트랜잭션 안에서 발행까지 마친다 — 커밋 전에 인스턴스가
 * 죽어도 락이 풀려 다른 인스턴스/다음 폴링이 재시도할 수 있다(at-least-once, 수신측 멱등 처리는 FastAPI 책임).
 * {@code @EnableScheduling}을 이 클래스에 직접 붙여 스케줄링을 자체 활성화한다(다른 설정 파일에 얹지 않음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class OutboxRelay {

  // 폴링 1회당 처리 상한 — 트랜잭션(=SKIP LOCKED 락 보유) 유지 시간을 짧게 묶어둔다.
  // 최악 락 점유 = BATCH_SIZE × SEND_TIMEOUT_SECONDS = 5 × 5s = 25s (relay의 tx timeout 30s가 백스톱).
  private static final int BATCH_SIZE = 5;
  // 이 횟수를 넘겨 실패하면 FAILED로 파킹(더 이상 자동 재시도하지 않음) — 무한 재시도로 인한 적체 방지.
  private static final int MAX_ATTEMPTS = 5;
  // 브로커 응답 대기 상한 — 폴러 스레드가 무한정 블록되지 않도록 한다.
  private static final long SEND_TIMEOUT_SECONDS = 5;

  private final OutboxRepository outboxRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;

  /** 이전 실행이 끝난 뒤 2초 후 재실행 — 폴링 간 최소 간격을 보장해 배치 처리가 길어져도 중첩 실행을 막는다. */
  @Scheduled(fixedDelay = 2000)
  @Transactional(timeout = 30)
  public void relay() {
    List<OutboxEvent> events = outboxRepository.findBatchForRelay(BATCH_SIZE);
    for (OutboxEvent event : events) {
      send(event);
    }
  }

  private void send(OutboxEvent event) {
    try {
      kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
          .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      event.markSent();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      markFailed(event, ex);
    } catch (ExecutionException | TimeoutException | RuntimeException ex) {
      // RuntimeException까지 잡는 이유: kafkaTemplate.send()가 (드물게) 동기적으로 던지는
      // 미확인 예외(예: 직렬화 오류)까지 여기서 흡수해야, 배치 안에서 먼저 처리된 다른 이벤트의
      // markSent()가 이 트랜잭션 롤백에 함께 휩쓸려 중복 발행되는 상황을 막을 수 있다.
      markFailed(event, ex);
    }
  }

  private void markFailed(OutboxEvent event, Exception ex) {
    event.markAttemptFailed(MAX_ATTEMPTS);
    log.warn("아웃박스 이벤트 발행 실패. id={}, topic={}, attempts={}",
        event.getId(), event.getTopic(), event.getAttempts(), ex);
  }
}
