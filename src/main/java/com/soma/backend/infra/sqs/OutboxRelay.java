package com.soma.backend.infra.sqs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;

import com.soma.backend.infra.outbox.OcrOutboxEvent;
import com.soma.backend.infra.outbox.OcrOutboxRepository;

/**
 * 아웃박스 릴레이. PENDING 이벤트를 고정 지연(fixedDelay) 폴링으로 조회해 SQS로 발행한다(design.md §4).
 * {@code FOR UPDATE SKIP LOCKED}로 조회한 행은 같은 트랜잭션 안에서 발행까지 마친다 — 커밋 전에 인스턴스가
 * 죽어도 락이 풀려 다른 인스턴스/다음 폴링이 재시도할 수 있다(at-least-once, 수신측 멱등 처리는 FastAPI 책임).
 * {@code @EnableScheduling}을 이 클래스에 직접 붙여 스케줄링을 자체 활성화한다(다른 설정 파일에 얹지 않음).
 *
 * <p>이벤트의 {@code topic}은 대상 SQS 큐 이름(예: ocr-job-queue)으로 쓰인다 — 큐 URL은 최초 1회 GetQueueUrl로
 * 해석해 캐시한다. {@code app.outbox.enabled=false}면 폴링을 건너뛴다(테스트·SQS 미가용 로컬).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
public class OutboxRelay {

  // 폴링 1회당 처리 상한 — 트랜잭션(=SKIP LOCKED 락 보유) 유지 시간을 짧게 묶어둔다.
  // 최악 락 점유 = BATCH_SIZE × apiCallTimeout(SqsConfig) = 5 × 5s = 25s (relay의 tx timeout 30s가 백스톱).
  private static final int BATCH_SIZE = 5;
  // 이 횟수를 넘겨 실패하면 FAILED로 파킹(더 이상 자동 재시도하지 않음) — 무한 재시도로 인한 적체 방지.
  private static final int MAX_ATTEMPTS = 5;

  private final OcrOutboxRepository outboxRepository;
  private final SqsClient sqsClient;

  // topic(=SQS 큐 이름) → 큐 URL 캐시. GetQueueUrl 호출을 큐당 1회로 줄인다.
  private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();

  // OutboxProcessor와 같은 플래그로 릴레이를 끈다(테스트·SQS 미가용 로컬). @EnableScheduling은 유지해야
  // 다른 스케줄러(OutboxProcessor)가 살아있으므로, 빈 조건부 대신 실행 시점 플래그로 no-op 처리한다.
  @Value("${app.outbox.enabled:true}")
  private boolean outboxEnabled;

  /** 이전 실행이 끝난 뒤 2초 후 재실행 — 폴링 간 최소 간격을 보장해 배치 처리가 길어져도 중첩 실행을 막는다. */
  @Scheduled(fixedDelay = 2000)
  @Transactional(timeout = 30)
  public void relay() {
    if (!outboxEnabled) {
      return;
    }
    List<OcrOutboxEvent> events = outboxRepository.findBatchForRelay(BATCH_SIZE);
    for (OcrOutboxEvent event : events) {
      send(event);
    }
  }

  private void send(OcrOutboxEvent event) {
    try {
      String queueUrl = resolveQueueUrl(event.getTopic());
      sqsClient.sendMessage(builder -> builder.queueUrl(queueUrl).messageBody(event.getPayload()));
      event.markSent();
    } catch (RuntimeException ex) {
      // SdkException(타임아웃·직렬화·네트워크 등)을 폭넓게 흡수한다 — 한 이벤트의 실패가 이 트랜잭션을
      // 롤백시켜, 배치 안에서 먼저 markSent()된 다른 이벤트가 중복 발행되는 상황을 막기 위함이다.
      markFailed(event, ex);
    }
  }

  private String resolveQueueUrl(String queueName) {
    return queueUrlCache.computeIfAbsent(queueName,
        name -> sqsClient.getQueueUrl(builder -> builder.queueName(name)).queueUrl());
  }

  private void markFailed(OcrOutboxEvent event, Exception ex) {
    event.markAttemptFailed(MAX_ATTEMPTS);
    log.warn("아웃박스 이벤트 발행 실패. id={}, queue={}, attempts={}",
        event.getId(), event.getTopic(), event.getAttempts(), ex);
  }
}
