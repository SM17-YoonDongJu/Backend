-- =====================================================================
-- V7 — REPORT_HOLDS에 보류 사유 추가(검수 대기 보류 모달).
-- reason: HoldReason enum 이름(대문자) 저장. NEED_MORE_DOCUMENTS / OUT_OF_SPECIALTY /
--         SCHEDULE_CONFLICT / OTHER. NOT NULL.
-- reason_detail: 직접 입력(OTHER) 또는 프리셋 사유의 상세 텍스트(선택). NULL 허용.
-- 기존 행은 사유 정보가 없으므로 OTHER로 백필한 뒤 NOT NULL 제약을 건다.
-- =====================================================================
ALTER TABLE report_holds ADD COLUMN reason varchar(30);
UPDATE report_holds SET reason = 'OTHER' WHERE reason IS NULL;
ALTER TABLE report_holds ALTER COLUMN reason SET NOT NULL;
ALTER TABLE report_holds ADD COLUMN reason_detail text;
