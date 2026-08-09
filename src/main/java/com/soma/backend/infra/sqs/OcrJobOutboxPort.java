package com.soma.backend.infra.sqs;

/**
 * OCR 트리거 발행 등록 포트(design.md §7 공유 계약 — 시그니처 고정, 임의 변경 금지).
 * backend-developer가 리포트 생성 트랜잭션 안에서 문서 1건당 호출한다.
 * 구현체는 호출자의 트랜잭션 안에서 {@code KafkaOutboxEvent}(topic="ocr-job-queue"=SQS 큐 이름,
 * messageKey=job.jobId())를 저장하기만 하고, 실제 SQS 발행은 별도 스케줄러
 * ({@link OutboxRelay})가 비동기로 수행한다 — 이 메서드는 SQS 가용성에 의존하지 않는다.
 */
public interface OcrJobOutboxPort {

  void enqueue(OcrJob job);
}
