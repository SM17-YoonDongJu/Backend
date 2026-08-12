-- =====================================================================
-- V39 — PII 컬럼 암호화: user_claims.details(이슈 #235).
-- V34/V36에서 report_worker의 jsonb 읽기 안정화를 기다리며 보류했던 마지막 컬럼이다. details는
-- Spring만 쓰고(UserClaim.create) report_worker는 읽기만 하는 구조라 description·question과 같은
-- 범주로 분류돼 있었다.
--
-- jsonb는 JSON 문자열로 직렬화한 뒤 통째로 1회 암호화해 bytea에 담는다(user_insurances.coverages와
-- 동일 패턴) — jsonb 경로 연산자(->·->>)나 GIN 인덱스는 details에 쓰인 적이 없어(엔티티 경유 조회만)
-- 조회 영향이 없다.
--
-- ⚠️ 파괴적 변경: jsonb → bytea는 무손실 캐스트가 없다.
--    아래 가드 블록이 "대상 컬럼에 값이 있는 행"을 발견하면 마이그레이션을 실패시킨다.
--    배포 전 dev의 기존 3건(전부 테스트 데이터로 확인)은 UPDATE로 미리 비웠다.
-- =====================================================================

DO $$
DECLARE
    plain_rows bigint;
BEGIN
    SELECT count(*) INTO plain_rows FROM user_claims WHERE details IS NOT NULL;
    IF plain_rows > 0 THEN
        RAISE EXCEPTION
            'user_claims.details에 평문 %건이 남아 있습니다 — V39를 적용하지 말고 백필 절차(design.md 9.2)를 먼저 수행하세요',
            plain_rows;
    END IF;
END $$;

ALTER TABLE user_claims
    ALTER COLUMN details TYPE bytea USING NULL;

COMMENT ON COLUMN user_claims.details IS
    'AES-256-GCM 암호화(봉투, JSON 문자열 직렬화 후 통째 암호화). AAD=user_claims:details. '
    '앱 컨버터 전용 — SQL 직접 조회 금지';
