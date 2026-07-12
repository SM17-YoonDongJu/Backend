-- =====================================================================
-- V8 — ERD 정합(2026-07): 팀 Notion ERD와 스키마 드리프트 해소(안전·추가/확장분만).
-- 파괴적·구조적 변경(USERS email→phone_number 전환, USER_CLAIMS.diagnosis text→text[] 등)은
-- 코드 동반 변경이 필요해 본 마이그레이션에서 제외한다.
-- =====================================================================

-- ADJUSTER_PROFILES: 평점 비정규화 컬럼(후기 등록 시 갱신). 집계 로직은 후기 POST에서 채운다.
ALTER TABLE adjuster_profiles ADD COLUMN rating_mean numeric;
ALTER TABLE adjuster_profiles ADD COLUMN review_count int;

-- USER_CLAIMS: ERD 정합.
--  - offered_amount: REPORTS 금액류(bigint)와 통일(int → bigint, 확장이라 무손실).
--  - question: 사용자 질문 입력 공통 컬럼(REPORTS.question로 승계).
--  - updated_at: 사건 정보 수정 플로우 대비.
ALTER TABLE user_claims ALTER COLUMN offered_amount TYPE bigint;
ALTER TABLE user_claims ADD COLUMN question text;
ALTER TABLE user_claims ADD COLUMN updated_at timestamp;
