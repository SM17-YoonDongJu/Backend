-- =====================================================================
-- V43 — reports.needs_reupload_notified_at 추가 (NEEDS_REUPLOAD 알림 멱등 가드).
--
-- OCR 품질 판정(신뢰도 미달 + 엔티티 미검출)으로 AI 워커가 리포트 생성을 건너뛸 때, 워커가
-- reports.status를 원시 SQL로 'NEEDS_REUPLOAD'로 직접 갱신한다(Backend 도메인 메서드를 거치지 않음).
-- 이 판정은 '실패'가 아니라 '품질 판정'이라 ai.ocr_job_failures(OCR 실패 저널)에 행이 남지 않으므로 —
-- AnalysisFailureNotificationSweeper(저널 기반)는 이 케이스를 절대 못 잡는다. BLOCKED와 같은 구조다.
-- 이 컬럼은 reports를 직접 스캔하는 별도 스윕(NeedsReuploadNotificationSweeper)의 단방향 멱등
-- 가드다. NULL이면 미통지.
--
-- NEEDS_REUPLOAD는 종료 상태이고(ReportStatus#NEEDS_REUPLOAD, ALLOWED_TRANSITIONS 자기 자신으로만)
-- 재판정 시나리오가 없으므로 — 재업로드는 새 리포트를 만든다 — 상태 자체가 아니라 통지 시각만
-- 저장해도 정상 회복 전이를 놓칠 위험이 없다(blocked_notified_at과 동일 판단).
--
-- status 값 자체에는 마이그레이션이 필요 없다. reports.status는 varchar(30)이고 CHECK 제약도
-- PostgreSQL enum도 없다(V1__init_schema.sql 8행 규약: "enum은 애플리케이션 @Enumerated(STRING)에
-- 맞춰 varchar로 둔다, CHECK 미부여"). 값 목록은 Java enum(ReportStatus)에서만 강제된다.
--
-- 영향: nullable + DEFAULT 없는 컬럼 추가라 PostgreSQL 11+에서 테이블 재작성 없이 즉시 끝난다.
--       기존 행은 전부 NULL(미통지)로 시작한다. 백필·인덱스 없음(스윕 대상이 소량이라 시퀀셜
--       스캔으로 충분 — 필요해지면 부분 인덱스는 별도 마이그레이션).
--
-- 스키마: 앱 테이블은 V40에서 core로 통일됐으므로 core.reports를 명시한다. ADD COLUMN은 컬럼 수준
--        변경이라 public CREATE 권한 없이도 통과한다(CLAUDE.md Key Configuration 권한 매트릭스).
-- =====================================================================
ALTER TABLE core.reports
  ADD COLUMN IF NOT EXISTS needs_reupload_notified_at timestamp;

COMMENT ON COLUMN core.reports.needs_reupload_notified_at IS
  'NEEDS_REUPLOAD(OCR 품질 미달, 재업로드 필요) 알림 발송 시각. NULL이면 미통지 — 해당 알림 스윕의 멱등 가드다.';
