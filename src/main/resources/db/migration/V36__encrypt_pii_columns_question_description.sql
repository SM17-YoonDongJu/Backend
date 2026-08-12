-- =====================================================================
-- V36 — PII 컬럼 암호화 3단계: reports.question · user_claims.description · user_claims.question.
-- V34에서 report_worker가 직접 읽는다는 이유로 보류했던 컬럼 중, 재조사 결과 Spring이 쓰고
-- report_worker는 읽기만 하는 것으로 확인된 3곳만 이번에 암호화한다(이슈 #228).
--
-- ⚠️ 파괴적 변경: text → bytea는 무손실 캐스트가 없다.
--    아래 가드 블록이 "대상 컬럼에 값이 있는 행"을 발견하면 마이그레이션을 실패시킨다.
--
-- 조회 영향 없음: 대상 컬럼은 WHERE·ORDER BY·LIKE·QueryDSL 프로젝션에 쓰이지 않는다(전수 확인).
-- user_claims.description을 셀렉트하던 native 프로젝션(ReportRepository.findReviewContext)은
-- 이 릴리즈에서 제거하고 UserClaim 엔티티 경유 조회로 옮겼다(native에는 컨버터가 적용되지 않는다).
--
-- 여전히 보류(이 마이그레이션 대상 아님):
--   - reports.treatment — writer가 report_worker로 확인됨(Spring 쓰기 경로 없음). PR-2 스코프.
--   - user_claims.details(jsonb) — writer는 Spring이지만 report_worker jsonb 읽기 fix 안정화 대기.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 가드: 평문 데이터가 남아 있으면 즉시 중단 (조용한 데이터 소실 방지)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    plain_rows bigint;
BEGIN
    SELECT count(*) INTO plain_rows FROM reports WHERE question IS NOT NULL;
    IF plain_rows > 0 THEN
        RAISE EXCEPTION
            'reports.question에 평문 %건이 남아 있습니다 — V36을 적용하지 말고 백필 절차(design.md 9.2)를 먼저 수행하세요',
            plain_rows;
    END IF;

    SELECT count(*) INTO plain_rows FROM user_claims WHERE description IS NOT NULL;
    IF plain_rows > 0 THEN
        RAISE EXCEPTION
            'user_claims.description에 평문 %건이 남아 있습니다 — V36을 적용하지 말고 백필 절차(design.md 9.2)를 먼저 수행하세요',
            plain_rows;
    END IF;

    SELECT count(*) INTO plain_rows FROM user_claims WHERE question IS NOT NULL;
    IF plain_rows > 0 THEN
        RAISE EXCEPTION
            'user_claims.question에 평문 %건이 남아 있습니다 — V36을 적용하지 말고 백필 절차(design.md 9.2)를 먼저 수행하세요',
            plain_rows;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- reports
-- ---------------------------------------------------------------------
ALTER TABLE reports
    ALTER COLUMN question TYPE bytea USING NULL;

COMMENT ON COLUMN reports.question IS
    'AES-256-GCM 암호화(봉투). AAD=reports:question. 앱 컨버터 전용 — SQL 직접 조회 금지';

-- ---------------------------------------------------------------------
-- user_claims
-- ---------------------------------------------------------------------
ALTER TABLE user_claims
    ALTER COLUMN description TYPE bytea USING NULL,
    ALTER COLUMN question    TYPE bytea USING NULL;

COMMENT ON COLUMN user_claims.description IS
    'AES-256-GCM 암호화(봉투). AAD=user_claims:description. 앱 컨버터 전용 — SQL 직접 조회 금지';
COMMENT ON COLUMN user_claims.question IS
    'AES-256-GCM 암호화(봉투). AAD=user_claims:question. 앱 컨버터 전용 — SQL 직접 조회 금지';
