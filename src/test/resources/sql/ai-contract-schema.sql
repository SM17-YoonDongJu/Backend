-- =====================================================================
-- ai.ocr_job_failures 테스트 부트스트랩 (AI 워커 소유 계약 테이블의 미러).
--
-- 원본: AI 레포 migrations/ai/008_ocr_job_failures.sql (동기화 일자: 2026-08-14)
-- ⚠️ 이 파일은 사본이다. 원본이 바뀌면 함께 갱신해야 하며, 리뷰 시 원본과 대조한다.
--
-- 왜 필요한가: OcrJobFailureView는 @Subselect라 Hibernate DDL 생성 대상이 아니다(운영에서 남의 팀
-- 테이블에 ddl-auto=validate가 묶이지 않게 하려는 의도적 선택). 그래서 test 프로파일
-- (ddl-auto: create-drop, flyway 비활성)에서는 이 테이블이 만들어지지 않아, 실패 저널을 읽는
-- 테스트가 직접 준비해야 한다.
--
-- 사용: @Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = BEFORE_TEST_CLASS)
--
-- ⚠️ 운영/개발 DB에는 절대 적용하지 않는다. Backend Flyway는 ai 스키마에 어떤 DDL도 넣지 않는다.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS ai;

CREATE TABLE IF NOT EXISTS ai.ocr_job_failures (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          uuid UNIQUE,
    message_id      text,
    s3_key          text,
    user_ref        text,
    content_type    text,
    doc_type_hint   text,
    claim_id        text,
    report_id       uuid,
    attachment_id   uuid,
    failure_class   text NOT NULL CHECK (failure_class IN
                    ('masking_residual','unreadable_file','ocr_error','schema_invalid','unknown')),
    error_type      text,
    attempts        int NOT NULL DEFAULT 1,
    terminal        boolean NOT NULL DEFAULT false,
    first_failed_at timestamptz NOT NULL DEFAULT now(),
    last_failed_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ocr_job_failures_terminal_idx
    ON ai.ocr_job_failures (last_failed_at) WHERE terminal;

-- =====================================================================
-- ai.ocr_results 테스트 부트스트랩 (AI 워커 소유 계약 테이블의 미러) — 문서별 OCR 품질 판정.
--
-- GRANT SELECT (id, report_id, claim_id, attachment_id, doc_type, doc_index, ocr_quality)
--   ON ai.ocr_results TO app_owner (AI 레포 PR #66, 아직 미배포 — id 컬럼은 OcrResultView의
--   Hibernate 식별자 요구사항 때문에 Backend QA 피드백으로 추가됨).
--
-- Backend는 이 테이블을 만들지 않는다(운영·개발 DB엔 절대 적용 금지) — OcrResultView가 @Subselect라
-- Hibernate DDL 생성 대상이 아니므로, 조회를 검증하는 테스트만 이 스크립트로 직접 준비한다.
-- =====================================================================
CREATE TABLE IF NOT EXISTS ai.ocr_results (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id    uuid,
    claim_id     text,
    attachment_id uuid,
    doc_type     text,
    doc_index    int,
    ocr_quality  text NOT NULL
);
