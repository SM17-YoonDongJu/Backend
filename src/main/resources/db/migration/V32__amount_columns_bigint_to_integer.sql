-- =====================================================================
-- V32 — 금액류 컬럼 bigint → integer 축소.
-- REPORTS·REPORT_REVIEWS·REPORT_ISSUES·REPORT_ISSUES_REVIEWS·USER_CLAIMS의 금액 필드를
-- Integer(Java)로 통일하면서 컬럼 타입도 맞춘다. V8에서 int → bigint로 확장했던 결정을 되돌리는
-- 것이라 개별 값이 int4 범위(약 21억)를 넘으면 실패한다 — 적용 전 데이터 범위를 확인할 것.
-- =====================================================================

ALTER TABLE reports ALTER COLUMN claimed_min_amount TYPE integer;
ALTER TABLE reports ALTER COLUMN claimed_max_amount TYPE integer;
ALTER TABLE reports ALTER COLUMN offered_amount TYPE integer;

ALTER TABLE report_reviews ALTER COLUMN estimate_min_amount TYPE integer;
ALTER TABLE report_reviews ALTER COLUMN estimate_max_amount TYPE integer;

ALTER TABLE report_issues ALTER COLUMN impact_amount TYPE integer;

ALTER TABLE report_issues_reviews ALTER COLUMN impact_amount TYPE integer;

ALTER TABLE user_claims ALTER COLUMN offered_amount TYPE integer;
