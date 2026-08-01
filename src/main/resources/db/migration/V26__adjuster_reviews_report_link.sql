-- 사건 단위 평점(GET /adjusters/me/reviewed-reports rating)을 위해 adjuster_reviews를 리포트에 연결한다.
-- 기존 행은 연결이 없어 nullable. 신규 평가부터 report_id를 채운다(평가 수집 쓰기 경로는 별도 티켓).
-- NOTE(버전): feat/80(채팅)이 V15를 사용하므로 중복을 피해 V16으로 잡음 — develop 통합 시 번호 확인 필요.
ALTER TABLE adjuster_reviews ADD COLUMN report_id uuid REFERENCES reports (id);
CREATE INDEX idx_adjuster_reviews_report ON adjuster_reviews (report_id);
