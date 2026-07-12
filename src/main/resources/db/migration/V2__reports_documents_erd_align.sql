-- =====================================================================
-- V2 — ERD 정합(2026-07): REPORTS에 첨부 비정규화 컬럼 추가
-- documents jsonb {name: s3_url} — 검수 대기 화면(API#6)의 첨부 서류 표기용.
-- 상세 첨부(검수 화면)는 report_attachments(리치)를 별도로 사용한다.
-- report_reviews.status·report_issues.ai_status의 enum 값 변경은 varchar 저장이라
-- 앱 레벨(@Enumerated)에서만 반영되므로 DDL 변경이 없다.
-- =====================================================================
ALTER TABLE reports ADD COLUMN documents jsonb;
