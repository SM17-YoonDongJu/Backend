-- =====================================================================
-- V45 — adjuster_profiles.cases_accepted·cases_reviewed 컬럼 제거.
--
-- 두 컬럼 모두 정식 write 경로가 없다(K6AdjusterSeedRunner 시드 삽입에서 0으로 채우는 게 유일한
-- 참조였다). 완료 건수는 이 컬럼을 신뢰하는 대신 report_reviews를 매번 실시간 집계해 쓴다
-- (AdjusterHomeQueryService·AdjusterProfileQueryService) — write 경로 없이 값을 믿을 수 없고,
-- 이중 계상 위험도 없앤다. 읽는 코드가 전혀 없던 죽은 컬럼이라 값 손실 없이 제거한다.
--
-- 스키마: 앱 테이블은 V40에서 core로 통일됐으므로 core.adjuster_profiles를 명시한다. DROP COLUMN은
--        컬럼 수준 변경이라 public CREATE 권한 없이도 통과한다(CLAUDE.md Key Configuration 권한 매트릭스).
-- =====================================================================
ALTER TABLE core.adjuster_profiles
  DROP COLUMN IF EXISTS cases_accepted,
  DROP COLUMN IF EXISTS cases_reviewed;
