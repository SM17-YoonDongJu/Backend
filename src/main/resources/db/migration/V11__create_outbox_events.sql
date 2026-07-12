-- 트랜잭션 아웃박스: 회원 탈퇴 후처리(Redis 부수효과)를 도메인 커밋과 원자적으로 적재하고
-- 커밋 이후 poller가 재시도하며 처리한다(dual-write 불일치 제거).
CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_type varchar(50) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(50) NOT NULL,
    payload text NOT NULL,
    status varchar(20) NOT NULL,
    retry_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    processed_at timestamp
);

-- poller 조회(WHERE status='PENDING' AND next_attempt_at <= now ORDER BY created_at)용 인덱스
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at);
