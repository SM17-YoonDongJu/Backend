-- 검수대기 목록(GET /reports/pending-review)의 ID 단계 조회와 건수 조회를 index-only scan으로 만든다(이슈 #376).
-- 1) report_reviews: "내 SENT·COUNSELING 검수가 있는 리포트 제외" anti-join이 (adjuster_id, status)로 찾아
--    report_id만 꺼내므로 세 컬럼을 모두 인덱스에 둔다.
-- 2) reports: status IN 필터 + created_at desc 정렬, 전문분야 정렬식(accident_type)·users 조인(user_id)·
--    ID 단계 select(id)가 전부 인덱스 안에 있어야 index-only가 된다. id를 INCLUDE에서 빼면 플래너가
--    이 인덱스를 쓰지 않는다(dev 실측).
-- CONCURRENTLY는 쓰지 않는다 — Flyway 마이그레이션 트랜잭션 안에서는 실행할 수 없고, 대상 테이블이 수만 행
-- 수준이라 일반 CREATE INDEX의 쓰기 잠금도 수백 ms에 끝난다.
-- 기존 idx_report_reviews_adjuster는 새 인덱스의 앞 컬럼과 겹치지만 다른 쿼리 영향 확인 범위 밖이라 남겨 둔다
-- (후속 정리 후보).
CREATE INDEX IF NOT EXISTS idx_report_reviews_adjuster_status_report
    ON core.report_reviews (adjuster_id, status, report_id);

CREATE INDEX IF NOT EXISTS idx_reports_status_created_at_covering
    ON core.reports (status, created_at DESC) INCLUDE (id, accident_type, user_id);
