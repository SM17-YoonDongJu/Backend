-- =====================================================================
-- V4 — ADJUSTER_PROFILES 평점 비정규화 (팀 ERD 2026-07-05 반영분)
-- rating_mean·review_count 컬럼 추가 + adjuster_reviews 집계로 백필.
-- 목적: GET /reports 카드 목록(ReportRepository.findUserReportCards)이 매 조회마다
--       adjuster_reviews 전체를 GROUP BY 하던 파생 테이블 병목 제거 → 컬럼 직접 조회.
-- 갱신 책임(recompute-on-write): 후기 등록/수정/삭제 시 해당 사정사만 재집계.
-- =====================================================================

ALTER TABLE adjuster_profiles
    ADD COLUMN rating_mean numeric,
    ADD COLUMN review_count int NOT NULL DEFAULT 0;

-- 기존 후기 백필. 후기가 없는 사정사는 rating_mean=NULL, review_count=0 유지
-- (adjuster_reviews.adjuster_id·adjuster_profiles.user_id 모두 users.id = 사정사 계정).
UPDATE adjuster_profiles ap
SET rating_mean = agg.avg_score,
    review_count = agg.cnt
FROM (
    SELECT adjuster_id,
           AVG(score) AS avg_score,
           COUNT(*)   AS cnt
    FROM adjuster_reviews
    GROUP BY adjuster_id
) agg
WHERE ap.user_id = agg.adjuster_id;
