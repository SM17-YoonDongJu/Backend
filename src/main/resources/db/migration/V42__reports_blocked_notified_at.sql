-- =====================================================================
-- V42 — reports.blocked_notified_at 추가 (BLOCKED 알림 멱등 가드).
--
-- AI 입력 가드레일이 접수를 차단하면 AI 워커가 reports.status를 원시 SQL로 'BLOCKED'로 직접
-- 갱신한다(Backend 도메인 메서드를 거치지 않음). BLOCKED는 ai.ocr_job_failures(OCR 실패 저널)에
-- 흔적을 남기지 않으므로 — 가드레일이 OCR·초안 생성 이전에 파이프라인을 끊기 때문 — 기존
-- AnalysisFailureNotificationSweeper(저널 기반)는 이 케이스를 절대 못 잡는다. 이 컬럼은 reports를
-- 직접 스캔하는 별도 스윕(BlockedReportNotificationSweeper)의 단방향 멱등 가드다. NULL이면 미통지.
--
-- BLOCKED는 종료 상태이고(ReportStatus#BLOCKED, ALLOWED_TRANSITIONS 자기 자신으로만) 재차단
-- 시나리오가 없으므로, analysis_failure_notified_at과 달리 상태 자체가 아니라 통지 시각만 저장해도
-- 정상 회복 전이를 놓칠 위험이 없다.
--
-- 영향: nullable + DEFAULT 없는 컬럼 추가라 PostgreSQL 11+에서 테이블 재작성 없이 즉시 끝난다.
--       기존 행은 전부 NULL(미통지)로 시작한다. 백필·인덱스 없음(스윕은 status='BLOCKED' 대상이
--       소량이라 시퀀셜 스캔으로 충분 — 필요해지면 부분 인덱스 추가는 별도 마이그레이션).
--
-- 스키마: 앱 테이블은 V40에서 core로 통일됐으므로 core.reports를 명시한다.
-- =====================================================================
ALTER TABLE core.reports
  ADD COLUMN IF NOT EXISTS blocked_notified_at timestamp;

COMMENT ON COLUMN core.reports.blocked_notified_at IS
  'BLOCKED(AI 입력 가드레일 차단) 알림 발송 시각. NULL이면 미통지 — BLOCKED 알림 스윕의 멱등 가드다.';
