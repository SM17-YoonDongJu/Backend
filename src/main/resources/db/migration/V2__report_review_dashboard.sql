-- backend#43 손해사정사 검수 대기 대시보드 / 리포트 검수 플로우
-- 최소 USERS/ADJUSTER_PROFILES 동반 생성(A1) + REPORTS/REPORT_ISSUES/REPORT_REVIEWS/REPORT_REVIEW_ISSUES/REPORT_HOLDS
-- 리더 확정: specialty_match_count 제거로 speciality 매핑 컬럼 불필요, review_due_at 컬럼 신설하지 않음(due_soon은 created_at 기준 계산)

CREATE TABLE users (
    id uuid PRIMARY KEY,
    role varchar(50) NOT NULL,
    nickname varchar(100),
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

CREATE TABLE reports (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    adjuster_id uuid,
    case_no varchar(100) NOT NULL UNIQUE,
    title varchar(255),
    accident_type varchar(30) NOT NULL,
    status varchar(30) NOT NULL,
    region varchar(100),
    claimed_min_amount bigint,
    claimed_max_amount bigint,
    offered_amount bigint,
    created_at timestamp NOT NULL,
    updated_at timestamp
);

CREATE INDEX idx_reports_status ON reports (status);
CREATE INDEX idx_reports_accident_type ON reports (accident_type);
CREATE INDEX idx_reports_region ON reports (region);
CREATE INDEX idx_reports_status_accident_type_region ON reports (status, accident_type, region);
CREATE INDEX idx_reports_created_at ON reports (created_at);

CREATE TABLE report_issues (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    content text,
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

CREATE TABLE report_reviews (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports (id),
    adjuster_id uuid NOT NULL,
    applicable_guarantees text,
    omitted_special_contract text,
    review text,
    outcome varchar(30),
    signed_at timestamp,
    adjuster_license_no varchar(100),
    adjuster_name varchar(100),
    created_at timestamp NOT NULL,
    updated_at timestamp,
    CONSTRAINT uk_report_reviews_report_adjuster UNIQUE (report_id, adjuster_id)
);

CREATE INDEX idx_report_reviews_adjuster ON report_reviews (adjuster_id);
CREATE INDEX idx_report_reviews_adjuster_created_at ON report_reviews (adjuster_id, created_at);

CREATE TABLE report_review_issues (
    id uuid PRIMARY KEY,
    report_review_id uuid NOT NULL REFERENCES report_reviews (id),
    report_issue_id uuid NOT NULL REFERENCES report_issues (id),
    review_status varchar(30) NOT NULL,
    adjuster_opinion text,
    modified_reason text,
    excluded_reason text,
    created_at timestamp NOT NULL,
    updated_at timestamp,
    CONSTRAINT uk_report_review_issues_review_issue UNIQUE (report_review_id, report_issue_id)
);

CREATE INDEX idx_report_review_issues_review ON report_review_issues (report_review_id);
