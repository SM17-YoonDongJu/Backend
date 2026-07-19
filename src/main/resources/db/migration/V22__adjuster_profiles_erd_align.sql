-- =====================================================================
-- V12 — ADJUSTER_PROFILES ERD 정합: 프로필 도메인 엔티티 매핑에 필요한 누락 컬럼 추가.
-- adjuster_profiles를 AdjusterProfile 엔티티(domain/adjuster)로 매핑하면서 ERD의
-- registration_url·updated_at 컬럼을 채운다(추가만 — 무손실).
-- =====================================================================

ALTER TABLE adjuster_profiles ADD COLUMN registration_url text;
ALTER TABLE adjuster_profiles ADD COLUMN updated_at timestamp;
