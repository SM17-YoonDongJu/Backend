-- =====================================================================
-- V15 — 사정사 자격 신청(ADJUSTER_APPLICATIONS) 상세 정합.
-- Notion API 명세(POST/GET /users/adjuster-applications) 기준으로 신청서 컬럼을 보강하고,
-- 문서 심사 상태(LICENSE/REGISTRATION)를 별도 테이블로 정규화한다.
-- =====================================================================

-- 신청서 추가 항목: 소속(독립/법인)·활동지역·증빙 서류 URL·반려 시각.
ALTER TABLE adjuster_applications ADD COLUMN affiliation varchar(20);
ALTER TABLE adjuster_applications ADD COLUMN region varchar(100);
ALTER TABLE adjuster_applications ADD COLUMN registration_image_url varchar(500);
ALTER TABLE adjuster_applications ADD COLUMN rejected_at timestamp;

-- 문서 심사 상태(신청 1건 : 문서 N종). doc_type=LICENSE/REGISTRATION,
-- status=PENDING/APPROVED/RESUBMIT_REQUIRED. 신청당 문서 종류는 유일(UK).
CREATE TABLE adjuster_application_documents (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES adjuster_applications (id),
    doc_type varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp,
    CONSTRAINT uk_adj_app_doc UNIQUE (application_id, doc_type)
);
CREATE INDEX idx_adj_app_doc_application ON adjuster_application_documents (application_id);
