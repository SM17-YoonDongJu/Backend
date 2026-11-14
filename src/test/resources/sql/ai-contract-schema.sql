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
-- ai.report_job_failures 테스트 부트스트랩 (AI 워커 소유 계약 테이블의 미러) — 리포트 생성 실패 저널.
--
-- 원본: AI 레포 migrations/ai/012_report_job_failures.sql (동기화 일자: 2026-11-01, AI PR #75)
-- ⚠️ 사본이다. 원본이 바뀌면 함께 갱신하고 리뷰 시 원본과 대조한다. GRANT·pgaudit DO 블록은 테스트에
--    필요 없어 옮기지 않았다(테스트 롤이 테이블 소유자다).
-- =====================================================================
CREATE TABLE IF NOT EXISTS ai.report_job_failures (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id       uuid UNIQUE,
    message_id      text,
    claim_id        text,
    job_id          uuid,
    failure_class   text NOT NULL CHECK (failure_class IN
                    ('context_unavailable','llm_unavailable','persist_failed','schema_invalid','unknown')),
    error_type      text,
    attempts        int NOT NULL DEFAULT 1,
    terminal        boolean NOT NULL DEFAULT false,
    first_failed_at timestamptz NOT NULL DEFAULT now(),
    last_failed_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS report_job_failures_terminal_idx
    ON ai.report_job_failures (last_failed_at) WHERE terminal;

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

-- =====================================================================
-- ai.report_drafts 테스트 부트스트랩 (AI 워커 소유 계약 테이블의 미러) — 리포트 초안(draft jsonb).
--
-- 원본: AI 레포 migrations/ai/007_report_drafts.sql (동기화 일자: 2026-11-12, AI#92 머지 후 dev 사본)
-- ⚠️ 사본이다. 원본이 바뀌면 함께 갱신하고 리뷰 시 원본과 대조한다. pgaudit·GRANT DO 블록은 테스트에
--    필요 없어 옮기지 않았다(테스트 롤이 테이블 소유자다). Backend는 draft -> 'metrics'만 읽는다.
-- =====================================================================
CREATE TABLE IF NOT EXISTS ai.report_drafts (
    report_id   uuid PRIMARY KEY,
    draft       jsonb       NOT NULL,
    status      text        NOT NULL DEFAULT 'draft',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    CONSTRAINT report_drafts_status_check
        CHECK (status IN ('draft', 'signed', 'rejected'))
);

-- =====================================================================
-- ai.claim_readiness 테스트 부트스트랩 (AI 워커 소유 계약 테이블의 미러) — 청구 fan-in 판정·재업로드 사유.
--
-- 원본: AI 레포 migrations/ai/010_claim_readiness.sql + 013_claim_readiness_reupload_reason.sql
--       (동기화 일자: 2026-11-14, AI#105)
-- ⚠️ 사본이다. 원본이 바뀌면 함께 갱신하고 리뷰 시 원본과 대조한다. GRANT·pgaudit DO 블록은 옮기지 않았다
--    (테스트 롤이 테이블 소유자다). 운영 GRANT는 컬럼 한정(claim_id, report_id, missing_doc_types,
--    reupload_reason)이라 ClaimReadinessView가 다른 컬럼을 고르면 운영에서만 42501이 난다 — 소유자 롤로는 그냥
--    통과하므로 ClaimReadinessViewSubselectTest(컬럼 목록 고정)와 ReuploadReasonGrantIntegrationTest(컬럼 한정
--    GRANT 재현, superuser 접속이면 건너뜀)가 막는다.
--    reupload_reason은 013이 ALTER로 붙인 컬럼이라 맨 뒤에 둔다. CHECK 이름은 013과 같다.
-- =====================================================================
CREATE TABLE IF NOT EXISTS ai.claim_readiness (
    claim_id           text PRIMARY KEY,
    report_id          uuid NOT NULL,
    doc_total          int  NOT NULL,
    terminal_job_ids   text[] NOT NULL DEFAULT '{}',
    docs_terminal      int GENERATED ALWAYS AS (cardinality(terminal_job_ids)) STORED,
    missing_doc_types  text[] NOT NULL DEFAULT '{}',
    status             text NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending','blocked','published')),
    judged_at          timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    reupload_reason    text,
    CONSTRAINT claim_readiness_reupload_reason_check
        CHECK (reupload_reason IN ('unreadable', 'off_domain', 'missing_required'))
);
