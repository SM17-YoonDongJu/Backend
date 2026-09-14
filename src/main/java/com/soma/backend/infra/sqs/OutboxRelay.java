package com.soma.backend.infra.sqs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;

import com.soma.backend.infra.outbox.OcrOutboxEvent;
import com.soma.backend.infra.outbox.OcrOutboxRepository;
import com.soma.backend.infra.outbox.OcrOutboxStatus;

/**
 * 아웃박스 릴레이. PENDING 이벤트를 고정 지연(fixedDelay) 폴링으로 조회해 SQS로 발행한다(design.md §4).
 * {@code FOR UPDATE SKIP LOCKED}로 조회한 행은 같은 트랜잭션 안에서 발행까지 마친다 — 커밋 전에 인스턴스가
 * 죽어도 락이 풀려 다른 인스턴스/다음 폴링이 재시도할 수 있다(at-least-once, 수신측 멱등 처리는 FastAPI 책임).
 * {@code @EnableScheduling}을 이 클래스에 직접 붙여 스케줄링을 자체 활성화한다(다른 설정 파일에 얹지 않음).
 *
 * <p>이벤트의 {@code topic}은 대상 SQS 큐 이름(예: ocr-job-queue)으로 쓰인다 — 큐 URL은 최초 1회 GetQueueUrl로
 * 해석해 캐시한다. {@code app.outbox.enabled=false}면 폴링을 건너뛴다(테스트·SQS 미가용 로컬).
 *
 * <p>발행 결과는 로그와 메트릭({@code outbox.relay.sent}·{@code outbox.relay.failed}, 태그 queue) 양쪽에
 * 남긴다 — 성공 흔적이 없으면 "발행이 되고 있는가"를 DB 조회로만 확인할 수 있어, 로그 수집이 끊긴 상황에서
 * 진단이 막힌다. 폴링은 2초마다 돌지만 보낼 이벤트가 없으면 아무 로그도 남기지 않는다.
 */
@Slf4j
@Component
@EnableScheduling
public class OutboxRelay {

  // 폴링 1회당 처리 상한 — 트랜잭션(=SKIP LOCKED 락 보유) 유지 시간을 짧게 묶어둔다.
  // 최악 락 점유 = BATCH_SIZE × apiCallTimeout(SqsConfig) = 5 × 5s = 25s (relay의 tx timeout 30s가 백스톱).
  private static final int BATCH_SIZE = 5;
  // 이 횟수를 넘겨 실패하면 FAILED로 파킹(더 이상 자동 재시도하지 않음) — 무한 재시도로 인한 적체 방지.
  private static final int MAX_ATTEMPTS = 5;
  // 발행 결과 카운터. 태그는 queue(=아웃박스 topic) 하나뿐이라 카디널리티가 낮다.
  private static final String METRIC_SENT = "outbox.relay.sent";
  private static final String METRIC_FAILED = "outbox.relay.failed";
  // 적재(created_at)→발행까지의 지연. 카운터만으론 "발행이 되고 있다"까지만 알 뿐, 202를 받은 뒤
  // OCR 트리거가 실제로 나가기까지 얼마나 걸리는지는 알 수 없었다(#306).
  private static final String METRIC_LATENCY = "outbox.relay.latency";
  // 스크레이프 시점의 status별 적체 깊이. 릴레이(2초 × 배치 5)가 생성 속도를 못 따라가면 PENDING이
  // 쌓이는데 그동안 지표가 없어 DB를 직접 조회해야만 보였다.
  private static final String METRIC_BACKLOG = "outbox.events";

  private final OcrOutboxRepository outboxRepository;
  private final SqsClient sqsClient;
  private final MeterRegistry meterRegistry;

  // topic(=SQS 큐 이름) → 큐 URL 캐시. GetQueueUrl 호출을 큐당 1회로 줄인다.
  private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();

  // OutboxProcessor와 같은 플래그로 릴레이를 끈다(테스트·SQS 미가용 로컬). @EnableScheduling은 유지해야
  // 다른 스케줄러(OutboxProcessor)가 살아있으므로, 빈 조건부 대신 실행 시점 플래그로 no-op 처리한다.
  @Value("${app.outbox.enabled:true}")
  private boolean outboxEnabled;

  public OutboxRelay(
      OcrOutboxRepository outboxRepository, SqsClient sqsClient, MeterRegistry meterRegistry) {
    this.outboxRepository = outboxRepository;
    this.sqsClient = sqsClient;
    this.meterRegistry = meterRegistry;
    registerBacklogGauges(outboxRepository, meterRegistry);
  }

  /**
   * 적체 게이지 등록. 값은 스크레이프 시점에 평가되므로 폴링 주기(2초)와 무관하게 DB 카운트가
   * 스크레이프 간격으로만 나간다 — {@code idx_kafka_outbox_status_created}가 받는 단순 카운트다.
   * FAILED는 MAX_ATTEMPTS 초과로 파킹돼 자동 재시도가 끊긴 상태라 운영 개입 신호로 따로 뽑는다.
   */
  private static void registerBacklogGauges(OcrOutboxRepository repository, MeterRegistry registry) {
    for (OcrOutboxStatus status : List.of(OcrOutboxStatus.PENDING, OcrOutboxStatus.FAILED)) {
      Gauge.builder(METRIC_BACKLOG, repository, repo -> repo.countByStatus(status))
          .description("아웃박스 이벤트 적체 — 스크레이프 시점의 status별 행 수")
          .tag("status", status.name())
          .register(registry);
    }
  }

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
      return;
    }
    // 계측·로그는 catch 밖에 둔다. 위 catch가 RuntimeException을 통째로 삼키므로 안에 두면 관측 코드의
    // 사소한 오류(예: 지연 계산의 NPE)가 "SQS 발행 실패"로 둔갑해 attempts를 올리고 failed 카운터를
    // 증가시킨다 — 실제로 발행은 성공한 상태인데도. 관측이 비즈니스 결과를 바꾸면 안 된다.
    meterRegistry.counter(METRIC_SENT, "queue", event.getTopic()).increment();
    recordRelayLatency(event);
    log.info("아웃박스 이벤트 발행 완료. id={}, queue={}", event.getId(), event.getTopic());
  }

  /**
   * 적재→발행 지연 기록. {@code createdAt}은 {@code @CreationTimestamp}라 DB를 거치지 않은 엔티티
   * (단위 테스트에서 직접 만든 객체 등)에서는 비어 있으므로 그 경우 조용히 건너뛴다.
   */
  private void recordRelayLatency(OcrOutboxEvent event) {
    if (event.getCreatedAt() == null || event.getSentAt() == null) {
      return;
    }
    meterRegistry.timer(METRIC_LATENCY, "queue", event.getTopic())
        .record(Duration.between(event.getCreatedAt(), event.getSentAt()));
  }

  private String resolveQueueUrl(String queueName) {
    return queueUrlCache.computeIfAbsent(queueName,
        name -> sqsClient.getQueueUrl(builder -> builder.queueName(name)).queueUrl());
  }

  private void markFailed(OcrOutboxEvent event, Exception ex) {
    event.markAttemptFailed(MAX_ATTEMPTS);
    meterRegistry.counter(METRIC_FAILED, "queue", event.getTopic()).increment();
    log.warn("아웃박스 이벤트 발행 실패. id={}, queue={}, attempts={}, status={}",
        event.getId(), event.getTopic(), event.getAttempts(), event.getStatus(), ex);
  }
}
