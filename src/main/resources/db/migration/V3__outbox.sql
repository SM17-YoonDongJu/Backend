CREATE TABLE outbox_events (
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
CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);
