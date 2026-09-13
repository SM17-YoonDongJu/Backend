-- =====================================================================
-- V46 — adjuster_profiles 비정규화 집계 컬럼 백필.
--
-- rating_mean·review_count·completed_consult_count는 write 경로 없이 읽히기만 해(이슈 #304) 전부
-- null로 남아 있다. 이번 PR이 붙이는 런타임 재집계와 **동일한 계산식**으로 기존 행을 한 번 채운다.
--
-- 원천에 행이 있는 프로필만 갱신한다(INNER JOIN 의미). 전량 재계산(LEFT JOIN + COALESCE 0)으로 하면
-- K6AdjusterSeedRunner가 넣은 부하테스트 시드 프로필의 평점(rating_mean=4.5·review_count=10)이 0으로 깎여
-- sort=rating·마이페이지 평점 시나리오가 깨진다 — 어떤 시더도 adjuster_reviews를 넣지 않아 대응하는 원천
-- 행이 없기 때문이다.
--
-- 단 completed_consult_count(시드값 12)까지 보존되는 것은 아니다: K6ScenarioSeedRunner는 K6AdjusterSeedRunner가
-- 만든 k6-adjuster-* 계정을 그대로 재사용하고, durable 방 시딩에서 review.accept()를 호출해
-- (K6ScenarioSeedWriter.seedDurableRoom) 그 사정사들에게 ACCEPTED report_reviews를 실제로 만든다. 따라서
-- 시나리오 시더까지 돌린 환경에서는 아래 두 번째 UPDATE가 12를 실제 채택 건수로 덮어쓴다(그 값이 정확한 값이다).
--
-- 스키마: 앱 테이블은 V40에서 core로 통일됐으므로 core.를 명시한다. UPDATE는 DML이라
--        public CREATE 권한과 무관하다(CLAUDE.md Key Configuration 권한 매트릭스).
-- 멱등: 재실행해도 같은 값이 나온다(대입식, 증분 아님).
-- =====================================================================

-- 평점(rating_mean·review_count) — score가 null인 행은 AVG·COUNT 양쪽에서 제외(모수 일치).
UPDATE core.adjuster_profiles ap
SET rating_mean = agg.avg_score,
    review_count = agg.review_count,
    updated_at   = now()
FROM (
  SELECT adjuster_id,
         ROUND(AVG(score)::numeric, 2) AS avg_score,
         COUNT(*)::int                 AS review_count
  FROM core.adjuster_reviews
  WHERE score IS NOT NULL
  GROUP BY adjuster_id
) agg
WHERE ap.user_id = agg.adjuster_id;

-- 상담 완료 수 — 사용자가 최종 채택한 제안(report_reviews.status = 'ACCEPTED') 건수.
UPDATE core.adjuster_profiles ap
SET completed_consult_count = agg.accepted_count,
    updated_at              = now()
FROM (
  SELECT adjuster_id, COUNT(*)::int AS accepted_count
  FROM core.report_reviews
  WHERE status = 'ACCEPTED'
  GROUP BY adjuster_id
) agg
WHERE ap.user_id = agg.adjuster_id;
