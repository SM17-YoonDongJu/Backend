-- 채팅(#80·#81): 상담 대상 제안(공유 리포트) 참조 + 읽음 커서(안읽음) + 목록 정렬 + 감사 컬럼
ALTER TABLE chatroom
  ADD COLUMN report_review_id      uuid REFERENCES report_reviews (id),
  ADD COLUMN user_last_read_at     timestamp,
  ADD COLUMN adjuster_last_read_at timestamp,
  ADD COLUMN last_message_at       timestamp,
  ADD COLUMN updated_at            timestamp;

-- 채팅 메시지: 타입 + 첨부(private S3 객체 key만 저장, 조회 시 presigned GET)
ALTER TABLE chatroom_messages
  ADD COLUMN message_type            varchar(20) NOT NULL DEFAULT 'TEXT',
  ADD COLUMN attachment_key          text,
  ADD COLUMN attachment_name         varchar(255),
  ADD COLUMN attachment_content_type varchar(100),
  ADD COLUMN attachment_size         bigint;

-- SYSTEM 메시지(상담 시작·종결 등)는 발신자가 없으므로 sender_id를 nullable로 완화
ALTER TABLE chatroom_messages
  ALTER COLUMN sender_id DROP NOT NULL;

-- 목록: 내 방들을 최근 활동 순으로
CREATE INDEX idx_chatroom_last_message_at ON chatroom (last_message_at DESC);

-- 메시지 커서 페이지네이션 + 안읽음 COUNT
CREATE INDEX idx_chatroom_messages_room_created
  ON chatroom_messages (room_id, created_at DESC, id DESC);
