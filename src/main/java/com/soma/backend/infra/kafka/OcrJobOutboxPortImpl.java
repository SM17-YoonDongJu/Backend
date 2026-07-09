package com.soma.backend.infra.kafka;

import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.infra.outbox.OutboxEvent;
import com.soma.backend.infra.outbox.OutboxRepository;

/**
 * {@link OcrJobOutboxPort} 구현체. OcrJob을 JSON 문자열로 직렬화해 OutboxEvent로 저장한다.
 * 이 메서드는 Kafka를 직접 호출하지 않는다 — 발행은 {@link OutboxRelay}가 별도 스케줄로 폴링해 수행한다
 * (Kafka 브로커 장애가 리포트 생성 트랜잭션에 영향을 주지 않도록 분리).
 * JsonMapper는 Spring Boot가 자동 구성한 전역 Bean을 그대로 재사용한다 — application.yml의
 * spring.jackson.property-naming-strategy: SNAKE_CASE가 이미 적용돼 있어 OcrJob의 camelCase 필드가
 * job_id/s3_key 같은 snake_case JSON으로 직렬화된다. 별도 모듈 등록이 필요 없다(OcrJob은 순수 String 필드만
 * 가진 평면 record라 design.md §3.2가 경고하는 다형성 FormatMapper 리스크와 무관).
 */
@Component
@RequiredArgsConstructor
public class OcrJobOutboxPortImpl implements OcrJobOutboxPort {

  private static final String TOPIC = "ocr-job-queue";
  private static final String AGGREGATE_TYPE = "OCR_JOB";

  private final OutboxRepository outboxRepository;
  private final JsonMapper jsonMapper;

  @Override
  public void enqueue(OcrJob job) {
    String payload = jsonMapper.writeValueAsString(job);
    UUID aggregateId = job.claimId() != null ? UUID.fromString(job.claimId()) : null;
    outboxRepository.save(OutboxEvent.pending(AGGREGATE_TYPE, aggregateId, TOPIC, job.jobId(), payload));
  }
}
