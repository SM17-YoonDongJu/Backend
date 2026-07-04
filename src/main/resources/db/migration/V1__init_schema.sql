-- =====================================================================
-- V1 baseline — 백엔드(Spring Boot) 담당 범위 + 경계 테이블
-- 출처: 팀 Notion ERD (윤동주팀). 노션에 없는 테이블은 생성하지 않는다.
-- 제외(FastAPI/RAG 소관): POLICY_CHUNKS, CASE_CHUNKS, search_terms,
--                        CHATBOT_SESSIONS, CHATBOT_MESSAGES
-- 경계 테이블(공유 DB, 백엔드가 DDL 소유·FastAPI가 rows 기록): OCR_RESULTS,
--            INSURERS, INSURANCE_PRODUCTS
-- enum은 애플리케이션 @Enumerated(STRING)/컨버터에 맞춰 varchar로 둔다(CHECK 미부여).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 회원 도메인
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id uuid PRIMARY KEY,
    nickname varchar(100) UNIQUE,
    email varchar(255),
    email_verified boolean,
    role varchar(30) NOT NULL,
    status varchar(20),
    gender varchar(10),
    region varchar(100),
    birth_date date,
    avatar_url varchar(500),
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp
);

CREATE TABLE social_accounts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    provider varchar(20) NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    linked_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_social_accounts_user ON social_accounts (user_id);

CREATE TABLE device_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    token varchar(500) NOT NULL,
    platform varchar(10) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);

CREATE TABLE notification_settings (
    user_id uuid PRIMARY KEY REFERENCES users (id),
    new_review_request boolean NOT NULL DEFAULT true,
    consult_message boolean NOT NULL DEFAULT true,
    settlement_notice boolean NOT NULL DEFAULT false,
    review_complete boolean NOT NULL DEFAULT true,
    received_proposal boolean NOT NULL DEFAULT true,
    marketing boolean NOT NULL DEFAULT false,
    updated_at timestamp
);

CREATE TABLE adjuster_profiles (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES users (id),
    license_no varchar(100) UNIQUE,
    name varchar(100),
    headline varchar(100),
    specialties text[],
    career int,
    cases_accepted int,
    cases_reviewed int,
    completed_consult_count int,
    careers jsonb,
    consult_methods text[],
    activity_region varchar(100),
    verified_at timestamp,
    introduction text,
    created_at timestamp NOT NULL DEFAULT now()
);

CREATE TABLE adjuster_applications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    name varchar(100) NOT NULL,
    speciality varchar(30),
    license_no varchar(100),
    license_image_url varchar(500),
    career int,
    introduction text,
    status varchar(20) NOT NULL,
    reject_reason text,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp
);
CREATE INDEX idx_adjuster_applications_user ON adjuster_applications (user_id);

-- ---------------------------------------------------------------------
-- 보험상품 도메인 (경계: 마스터 데이터)
-- ---------------------------------------------------------------------
CREATE TABLE insurers (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL
);

CREATE TABLE insurance_products (
    id uuid PRIMARY KEY,
    insurer_id uuid NOT NULL REFERENCES insurers (id),
    category varchar(30),
    product_name varchar(255),
    version varchar(50)
);
CREATE INDEX idx_insurance_products_insurer ON insurance_products (insurer_id);

-- ---------------------------------------------------------------------
-- OCR 결과 (경계: FastAPI가 rows 기록, 백엔드가 참조)
-- ---------------------------------------------------------------------
CREATE TABLE ocr_results (
    id uuid PRIMARY KEY,
    job_id uuid,
    doc_type text,
    masked_text text,
    entities jsonb,
    created_at timestamptz DEFAULT now(),
    doc_type_confidence real,
    ocr_confidence real,
    masked_lines jsonb,
    masked_image_s3_keys jsonb
);

-- ---------------------------------------------------------------------
-- 청구 도메인
-- ---------------------------------------------------------------------
CREATE TABLE user_claims (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    product_id uuid REFERENCES insurance_products (id),
    offered_amount int,
    accident_date date,
    hospitalization jsonb,
    diagnosis text,
    description text,
    additional_information text,
    accident_type varchar(30),
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_claims_user ON user_claims (user_id);

CREATE TABLE user_insurances (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    insurer_name varchar(255),
    product_name varchar(255),
    product_id uuid REFERENCES insurance_products (id),
    match_status varchar(20),
    policy_no varchar(100),
    enrolled_at date,
    coverages text[],
    policy_file_url text,
    ocr_result_id uuid REFERENCES ocr_results (id),
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_insurances_user ON user_insurances (user_id);

-- ---------------------------------------------------------------------
-- 리포트 도메인 (AI 초안 — 불변)
-- ---------------------------------------------------------------------
CREATE TABLE reports (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    adjuster_id uuid REFERENCES users (id),
    product_id uuid REFERENCES insurance_products (id),
    claim_id uuid REFERENCES user_claims (id),
    accident_type varchar(30) NOT NULL,
    treatment text,
    title varchar(255),
    claimed_min_amount bigint,
    claimed_max_amount bigint,
    offered_amount bigint,
    applicable_guarantees text[],
    omitted_special_contract text[],
    basis_terms_precedents text[],
    question text,
    confidence_level varchar(10),
    is_masked boolean,
    case_no varchar(100) NOT NULL UNIQUE,
    status varchar(30) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp
);
CREATE INDEX idx_reports_user ON reports (user_id);
CREATE INDEX idx_reports_status ON reports (status);
CREATE INDEX idx_reports_accident_type ON reports (accident_type);
CREATE INDEX idx_reports_created_at ON reports (created_at);

CREATE TABLE report_issues (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    title varchar(255),
    description text,
    ai_status varchar(20),
    tags text[],
    impact_amount bigint,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_issues_report ON report_issues (report_id);

-- 사정사별 검수·제안 (사정사 수정본 — AI 초안과 격리)
CREATE TABLE report_reviews (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    adjuster_id uuid NOT NULL REFERENCES users (id),
    review text,
    estimate_min_amount bigint,
    estimate_max_amount bigint,
    applicable_guarantees text[],
    omitted_special_contract text[],
    basis_terms_precedents text[],
    status varchar(30),
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp,
    CONSTRAINT uk_report_reviews_report_adjuster UNIQUE (report_id, adjuster_id)
);
CREATE INDEX idx_report_reviews_adjuster ON report_reviews (adjuster_id);
CREATE INDEX idx_report_reviews_adjuster_created_at ON report_reviews (adjuster_id, created_at);

-- 사정사별 쟁점 검수 (report_issue_id nullable = ADDED 신규 쟁점)
CREATE TABLE report_review_issues (
    id uuid PRIMARY KEY,
    report_review_id uuid NOT NULL REFERENCES report_reviews (id),
    report_issue_id uuid REFERENCES report_issues (id),
    title text,
    description text,
    review_status varchar(20) NOT NULL,
    adjuster_opinion text,
    modified_reason text,
    excluded_reason text,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_review_issues_review ON report_review_issues (report_review_id);
-- ADDED(report_issue_id NULL)는 검수 1건당 다건 허용, 기존 쟁점 검수는 유일
CREATE UNIQUE INDEX uk_report_review_issues_review_issue
    ON report_review_issues (report_review_id, report_issue_id)
    WHERE report_issue_id IS NOT NULL;

CREATE TABLE report_attachments (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    name varchar(255),
    mime_type varchar(100),
    url text,
    report_type varchar(30),
    page_count int,
    issued_by varchar(255),
    issued_at date,
    ai_summary text,
    ocr_result_id uuid REFERENCES ocr_results (id),
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_attachments_report ON report_attachments (report_id);

CREATE TABLE report_holds (
    id uuid PRIMARY KEY,
    adjuster_id uuid NOT NULL REFERENCES users (id),
    report_id uuid NOT NULL REFERENCES reports (id),
    created_at timestamp NOT NULL DEFAULT now(),
    CONSTRAINT uk_report_holds_adjuster_report UNIQUE (adjuster_id, report_id)
);
CREATE INDEX idx_report_holds_report ON report_holds (report_id);

-- ---------------------------------------------------------------------
-- 평가 도메인
-- ---------------------------------------------------------------------
CREATE TABLE adjuster_reviews (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    adjuster_id uuid NOT NULL REFERENCES users (id),
    score int,
    review text,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_adjuster_reviews_adjuster ON adjuster_reviews (adjuster_id);

-- ---------------------------------------------------------------------
-- 채팅 도메인 (WebSocket 상담 — 백엔드. AI 챗봇은 FastAPI라 제외)
-- ---------------------------------------------------------------------
CREATE TABLE chatroom (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    adjuster_id uuid NOT NULL REFERENCES users (id),
    report_id uuid REFERENCES reports (id),
    status varchar(20),
    last_message text,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_chatroom_user ON chatroom (user_id);
CREATE INDEX idx_chatroom_adjuster ON chatroom (adjuster_id);

CREATE TABLE chatroom_messages (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL REFERENCES chatroom (id),
    sender_id uuid NOT NULL REFERENCES users (id),
    content text,
    created_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_chatroom_messages_room ON chatroom_messages (room_id);

-- ---------------------------------------------------------------------
-- 구독 도메인
-- ---------------------------------------------------------------------
CREATE TABLE subscriptions (
    id uuid PRIMARY KEY,
    adjuster_id uuid NOT NULL REFERENCES users (id),
    plan varchar(20),
    billing_cycle varchar(20),
    status varchar(20),
    started_at timestamp,
    expires_at timestamp,
    next_billing_at timestamp
);
CREATE INDEX idx_subscriptions_adjuster ON subscriptions (adjuster_id);
