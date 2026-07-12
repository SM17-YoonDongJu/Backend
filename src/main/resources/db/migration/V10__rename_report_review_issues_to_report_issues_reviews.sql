-- ERD 정합: 사정사별 쟁점 검수 테이블명을 report_review_issues → report_issues_reviews로 변경
ALTER TABLE report_review_issues RENAME TO report_issues_reviews;

-- 인덱스명도 새 테이블명 규칙에 맞춰 정리(테이블 rename 시 인덱스명은 자동 변경되지 않음)
ALTER INDEX idx_report_review_issues_review RENAME TO idx_report_issues_reviews_review;
ALTER INDEX uk_report_review_issues_review_issue RENAME TO uk_report_issues_reviews_review_issue;
