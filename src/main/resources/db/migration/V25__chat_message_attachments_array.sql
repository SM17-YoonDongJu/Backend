-- 채팅 메시지 첨부를 단수 → 복수(jsonb 배열)로 전환 (FE 요청 #48: 한 메시지에 여러 첨부).
-- private S3 object key + 표시 메타만 배열로 담는다 (S3 키 스킴·업로드 방식은 불변).
ALTER TABLE chatroom_messages ADD COLUMN attachments jsonb;

-- 기존 단수 첨부가 있던 행은 1개짜리 배열로 이관 (Hibernate JSON 매퍼 키에 맞춰 camelCase).
UPDATE chatroom_messages
SET attachments = jsonb_build_array(
      jsonb_build_object(
        'attachmentKey', attachment_key,
        'name', attachment_name,
        'contentType', attachment_content_type,
        'size', attachment_size))
WHERE attachment_key IS NOT NULL;

-- 단수 첨부 컬럼 제거
ALTER TABLE chatroom_messages
  DROP COLUMN attachment_key,
  DROP COLUMN attachment_name,
  DROP COLUMN attachment_content_type,
  DROP COLUMN attachment_size;
