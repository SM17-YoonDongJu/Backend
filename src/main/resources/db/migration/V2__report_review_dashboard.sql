-- backend#43 후속(팀 ERD 정합, 2026-07-03): AI 초안/사정사 검수 격리 스키마 재정의
-- 원칙: REPORTS/REPORT_ISSUES(AI 초안, 불변·공유) vs REPORT_REVIEWS/REPORT_REVIEW_ISSUES(사정사별 검수, 격리)
-- 리더 확정: specialty_match_count 제거로 speciality 매핑 컬럼 불필요, review_due_at 컬럼 신설하지 않음(due_soon은 created_at 기준 계산)

CREATE TABLE users (
    id uuid PRIMARY KEY,
    role varchar(50) NOT NULL,
    nickname varchar(100),
    region varchar(100),
    status varchar(30),
    created_at timestamp NOT NULL,
    updated_at timestamp
);

CREATE TABLE adjuster_profiles (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE,
    license_no varchar(100) UNIQUE,
    speciality varchar(30),
    subscription_plan varchar(30),
    active boolean NOT NULL DEFAULT true,
    name varchar(100),
    created_at timestamp NOT NULL,
    updated_at timestamp
);

-- REPORTS: AI 초안 본체. region 컬럼 없음(users.region 조인). product_id/claim_id는 타 도메인 미존재로 FK 강제 안 함.
CREATE TABLE reports (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    adjuster_id uuid,
    product_id uuid,
    claim_id uuid,
    case_no varchar(100) NOT NULL UNIQUE,
    title varchar(255),
    accident_type varchar(30) NOT NULL,
    status varchar(30) NOT NULL,
    claimed_min_amount bigint,
    claimed_max_amount bigint,
    offered_amount bigint,
    applicable_guarantees text[],
    omitted_special_contract text[],
    basis_terms_precedents text[],
    treatment text,
    question text,
    created_at timestamp NOT NULL,
    updated_at timestamp
);

CREATE INDEX idx_reports_status ON reports (status);
CREATE INDEX idx_reports_accident_type ON reports (accident_type);
CREATE INDEX idx_reports_status_accident_type ON reports (status, accident_type);
CREATE INDEX idx_reports_created_at ON reports (created_at);
CREATE INDEX idx_reports_user_id ON reports (user_id);

-- REPORT_ISSUES: AI 쟁점 초안. 검수 결과 컬럼 없음(REPORT_REVIEW_ISSUES로 격리).
CREATE TABLE report_issues (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    title varchar(255),
    description text,
    ai_status varchar(30),
    tags text[],
    impact_amount bigint,
    created_at timestamp NOT NULL,
    updated_at timestamp
);

CREATE INDEX idx_report_issues_report_id ON report_issues (report_id);

CREATE TABLE report_holds (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    adjuster_id uuid NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp,
    CONSTRAINT uk_report_holds_report_adjuster UNIQUE (report_id, adjuster_id)
);

CREATE INDEX idx_report_holds_adjuster ON report_holds (adjuster_id);
CREATE INDEX idx_report_holds_report ON report_holds (report_id);

-- REPORT_REVIEWS: 사정사 최종 의견(고객 노출). (report_id, adjuster_id) 당 1행.
-- applicable_guarantees/omitted_special_contract/basis_terms_precedents는 REPORTS(AI 원본)와 별개인 사정사 수정본.
CREATE TABLE report_reviews (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    adjuster_id uuid NOT NULL,
    review text,
    estimate_min_amount bigint,
    estimate_max_amount bigint,
    applicable_guarantees text[],
    omitted_special_contract text[],
    basis_terms_precedents text[],
    status varchar(30) NOT NULL,
    created_at timestamp NOT NULL,
    updated_at timestamp,
    CONSTRAINT uk_report_reviews_report_adjuster UNIQUE (report_id, adjuster_id)
);

CREATE INDEX idx_report_reviews_adjuster ON report_reviews (adjuster_id);
CREATE INDEX idx_report_reviews_adjuster_created_at ON report_reviews (adjuster_id, created_at);

-- REPORT_REVIEW_ISSUES: 사정사별 쟁점 검수. report_issue_id가 nullable(= ADDED 신규 쟁점)이므로
-- 전체 UK 대신 부분(partial) unique index를 사용한다.
-- ACCEPTED/MODIFIED/EXCLUDED(기존 AI 쟁점 검수)는 (report_review_id, report_issue_id)가 유일해야 하지만
-- ADDED(report_issue_id = null, 사정사가 새로 추가한 쟁점)는 검수 1건당 여러 행을 허용해야 하므로
-- report_issue_id IS NOT NULL 조건의 부분 인덱스로 제한한다.
CREATE TABLE report_review_issues (
    id uuid PRIMARY KEY,
    report_review_id uuid NOT NULL REFERENCES report_reviews (id),
    report_issue_id uuid REFERENCES report_issues (id),
    title varchar(255),
    description text,
    review_status varchar(30) NOT NULL,
    adjuster_opinion text,
    modified_reason text,
    excluded_reason text,
    created_at timestamp NOT NULL,
    updated_at timestamp
);

CREATE UNIQUE INDEX uk_report_review_issues_review_issue
    ON report_review_issues (report_review_id, report_issue_id)
    WHERE report_issue_id IS NOT NULL;

CREATE INDEX idx_report_review_issues_review ON report_review_issues (report_review_id);
