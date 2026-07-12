-- Kafka 발행용 트랜잭셔널 아웃박스(OCR 트리거). 도메인 트랜잭션과 같은 커밋으로 적재하고
-- OutboxRelay가 폴링해 Kafka로 발행한다(at-least-once). develop의 outbox_events(회원 탈퇴 Redis 후처리)와
-- 목적·스키마가 달라 별도 테이블로 둔다.
CREATE TABLE kafka_outbox_events (
  id uuid PRIMARY KEY,
  aggregate_type varchar(50) NOT NULL,
  aggregate_id uuid,
  topic varchar(100) NOT NULL,
  message_key varchar(100) NOT NULL,   -- Kafka 파티션 키(job_id)
  payload jsonb NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING',  -- PENDING/SENT/FAILED
  attempts int NOT NULL DEFAULT 0,
  created_at timestamp NOT NULL DEFAULT now(),
  sent_at timestamp
);
CREATE INDEX idx_kafka_outbox_status_created ON kafka_outbox_events (status, created_at);
