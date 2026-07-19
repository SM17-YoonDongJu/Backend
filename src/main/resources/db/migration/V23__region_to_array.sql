-- =====================================================================
-- V13 — 지역(region)을 단일 varchar → 배열(text[])로 전환.
-- USERS.region(거주/활동 지역)과 ADJUSTER_PROFILES.activity_region(활동 지역)을 복수 지역으로 확장한다.
-- 기존 스칼라 값은 단일 원소 배열로 무손실 승계하고, NULL은 NULL로 유지한다.
-- =====================================================================

ALTER TABLE users
  ALTER COLUMN region TYPE text[]
  USING (CASE WHEN region IS NULL THEN NULL ELSE ARRAY[region] END);

ALTER TABLE adjuster_profiles
  ALTER COLUMN activity_region TYPE text[]
  USING (CASE WHEN activity_region IS NULL THEN NULL ELSE ARRAY[activity_region] END);
