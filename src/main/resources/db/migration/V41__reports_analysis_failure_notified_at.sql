-- =====================================================================
-- V41 — reports.analysis_failure_notified_at 추가 (OCR 처리 실패 알림 멱등 가드).
--
-- 분석(OCR·AI) 확정 실패를 사용자에게 통지한 시각을 기록한다. NULL이면 미통지이고, 스윕
-- (AnalysisFailureNotificationSweeper)이 이 컬럼이 NULL인 리포트만 골라 알림을 보낸 뒤 시각을
-- 채운다 — 60초 주기 폴링이 같은 실패로 알림을 반복 생성하는 것을 막는 단방향 가드다.
--
-- 실패 "상태" 자체는 저장하지 않는다. 상태는 ai.ocr_job_failures(AI 워커 소유)에서 조회 시점마다
-- 파생하는 값이라, 스냅샷으로 들고 있으면 워커가 실패를 회복해 행을 지웠을 때(clear_job_failure)
-- 그 전이를 놓쳐 사용자에게 영원히 실패로 남는다.
--
-- ⚠️ ai.ocr_job_failures는 AI 워커 소유다. 이 마이그레이션은 물론 앞으로의 어떤 Backend
--    마이그레이션도 ai 스키마에 CREATE/ALTER/GRANT를 하지 않는다(소유권 경계 + GRANT는 객체
--    소유자만 실행 가능해서 배포가 통째로 실패한다). 원본 DDL: AI 레포
--    migrations/ai/008_ocr_job_failures.sql, GRANT 정본: AI 레포 deploy/schema_split.sql.
--
-- 영향: nullable + DEFAULT 없는 컬럼 추가라 PostgreSQL 11+에서 테이블 재작성 없이 즉시 끝난다.
--       기존 행은 전부 NULL(미통지)로 시작해, 과거 실패 리포트도 스윕 lookback(7일) 창 안이면
--       통지 대상이 된다. 백필·인덱스 없음(스윕은 실패 뷰의 부분 인덱스로 구동하고 reports는
--       PK 조인만 한다).
--
-- 스키마: 앱 테이블은 V40에서 core로 통일됐으므로 core.reports를 명시한다.
-- =====================================================================
ALTER TABLE core.reports
  ADD COLUMN IF NOT EXISTS analysis_failure_notified_at timestamp;

COMMENT ON COLUMN core.reports.analysis_failure_notified_at IS
  '분석(OCR·AI) 실패 알림 발송 시각. NULL이면 미통지 — 실패 알림 스윕의 멱등 가드다.';
