-- 전제: user_claims는 런칭 전 빈 테이블이므로 diagnosis/hospitalization을 백필 없이 DROP한다(유실 데이터 없음).
-- 근거: 2026-07-06 ERD 확정(공통 컬럼 + details jsonb 통합). 데이터가 존재하는 환경에 적용 시 이 스크립트 전에 백필 필요.
ALTER TABLE user_claims ALTER COLUMN offered_amount TYPE bigint;
ALTER TABLE user_claims DROP COLUMN diagnosis;
ALTER TABLE user_claims DROP COLUMN hospitalization;
ALTER TABLE user_claims ADD  COLUMN details jsonb;
ALTER TABLE user_claims ADD  COLUMN question text;
ALTER TABLE user_claims ADD  COLUMN updated_at timestamp;
