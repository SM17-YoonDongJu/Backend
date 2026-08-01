-- 사정사가 쟁점을 수정(MODIFIED)하거나 신규 추가(ADDED)할 때 직접 입력하는 영향 금액.
-- AI 원본 금액은 report_issues.impact_amount에 있고, 사정사 확정본은 여기(오버레이)에 격리한다.
-- nullable — 금액을 손대지 않은 인정(ACCEPTED)/제외(EXCLUDED)나 금액 미입력 시 NULL.
ALTER TABLE report_review_issues ADD COLUMN impact_amount bigint;
