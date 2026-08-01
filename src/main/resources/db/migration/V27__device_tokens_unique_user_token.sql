-- device_tokens에 (user_id, token) UNIQUE 제약을 추가한다.
-- 등록 API의 upsert(같은 user+token 재등록 시 신규 행 금지)를 DB 레벨에서 보증한다.
-- 기존 테이블에는 UNIQUE가 없어 중복 행이 존재할 수 있으므로, 제약 추가 전에
-- (user_id, token)별로 가장 먼저 등록된 1건만 남기고 나머지를 제거한다(중복 정리).
DELETE FROM device_tokens dt
USING (
    SELECT id,
           row_number() OVER (
             PARTITION BY user_id, token
             ORDER BY created_at, id
           ) AS rn
    FROM device_tokens
) ranked
WHERE dt.id = ranked.id
  AND ranked.rn > 1;

ALTER TABLE device_tokens
    ADD CONSTRAINT uq_device_tokens_user_token UNIQUE (user_id, token);
