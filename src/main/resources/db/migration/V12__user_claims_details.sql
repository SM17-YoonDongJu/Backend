-- user_claims 상세를 details jsonb로 통합(sealed ClaimDetails 매핑). ERD 확정(2026-07-06) 공통 컬럼 + details jsonb.
-- offered_amount(bigint)·question·updated_at는 develop V8(erd_align_profiles_claims)이 이미 반영해 여기선 제외한다.
-- 전제: user_claims는 런칭 전 빈 테이블이라 diagnosis/hospitalization을 백필 없이 DROP한다(유실 데이터 없음).
ALTER TABLE user_claims DROP COLUMN diagnosis;
ALTER TABLE user_claims DROP COLUMN hospitalization;
ALTER TABLE user_claims ADD COLUMN details jsonb;
