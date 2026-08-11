-- =====================================================================
-- V34 — PII 컬럼 암호화 1단계(1/2): user_claims·user_insurances.
-- 대상 컬럼을 AES-256-GCM 봉투(bytea)로 전환한다. 암복호화는 애플리케이션(PiiCipher + AttributeConverter)이
-- 전담하므로 SQL로는 타입만 바꾼다 — DB에서 평문↔암호문 변환은 불가능하다(DEK가 KMS/앱 메모리에만 있음).
--
-- ⚠️ 파괴적 변경: text/varchar/date/text[] → bytea는 무손실 캐스트가 없다.
--    아래 가드 블록이 "대상 컬럼에 값이 있는 행"을 발견하면 마이그레이션을 실패시킨다.
--    가드가 걸리면 이 파일을 적용하지 말고 design.md §9.2 백필 절차(expand-migrate-contract)를 따른다.
--
-- 조회 영향 없음: 대상 컬럼은 WHERE·ORDER BY·LIKE·QueryDSL 프로젝션에 쓰이지 않는다(전수 확인).
-- user_claims.additional_information을 셀렉트하던 native 프로젝션(ReportRepository.findReviewContext)은
-- 이 릴리즈에서 제거하고 UserClaim 엔티티 경유 조회로 옮겼다(native에는 컨버터가 적용되지 않는다).
-- user_insurances는 쓰기 경로(POST /users/me/insurances) 미구현으로 비어 있다(V28 코멘트 참조).
--
-- 보류 컬럼(이 마이그레이션 대상 아님): reports.treatment·question,
-- user_claims.description·question·details — report_worker가 직접 읽는 컬럼이라 2단계로 미룬다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 가드: 평문 데이터가 남아 있으면 즉시 중단 (조용한 데이터 소실 방지)
-- ---------------------------------------------------------------------
DO $$
DECLARE
    plain_rows bigint;
BEGIN
    SELECT count(*) INTO plain_rows FROM user_claims WHERE additional_information IS NOT NULL;
    IF plain_rows > 0 THEN
        RAISE EXCEPTION
            'user_claims.additional_information에 평문 %건이 남아 있습니다 — V34를 적용하지 말고 백필 절차(design.md 9.2)를 먼저 수행하세요',
            plain_rows;
    END IF;

    SELECT count(*) INTO plain_rows FROM user_insurances;
    IF plain_rows > 0 THEN
        RAISE EXCEPTION
            'user_insurances에 %건이 남아 있습니다 — V34를 적용하지 말고 백필 절차(design.md 9.2)를 먼저 수행하세요',
            plain_rows;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- user_claims
-- ---------------------------------------------------------------------
ALTER TABLE user_claims
    ALTER COLUMN additional_information TYPE bytea USING NULL;

COMMENT ON COLUMN user_claims.additional_information IS
    'AES-256-GCM 암호화(봉투). AAD=user_claims:additional_information. 앱 컨버터 전용 — SQL 직접 조회 금지';

-- ---------------------------------------------------------------------
-- user_insurances — NOT NULL 컬럼은 제약 해제 → 타입 변경 → 제약 복원 순서를 지켜야 한다.
-- ---------------------------------------------------------------------
ALTER TABLE user_insurances
    ALTER COLUMN insurer_name DROP NOT NULL,
    ALTER COLUMN product_name DROP NOT NULL;

ALTER TABLE user_insurances
    ALTER COLUMN insurer_name TYPE bytea USING NULL,
    ALTER COLUMN product_name TYPE bytea USING NULL,
    ALTER COLUMN policy_no    TYPE bytea USING NULL,
    ALTER COLUMN enrolled_at  TYPE bytea USING NULL,
    ALTER COLUMN coverages    TYPE bytea USING NULL;

ALTER TABLE user_insurances
    ALTER COLUMN insurer_name SET NOT NULL,
    ALTER COLUMN product_name SET NOT NULL;

COMMENT ON COLUMN user_insurances.insurer_name IS
    'AES-256-GCM 암호화(봉투). AAD=user_insurances:insurer_name';
COMMENT ON COLUMN user_insurances.product_name IS
    'AES-256-GCM 암호화(봉투). AAD=user_insurances:product_name';
COMMENT ON COLUMN user_insurances.policy_no IS
    'AES-256-GCM 암호화(봉투). AAD=user_insurances:policy_no';
COMMENT ON COLUMN user_insurances.enrolled_at IS
    'AES-256-GCM 암호화(봉투, ISO_LOCAL_DATE 문자열). AAD=user_insurances:enrolled_at';
COMMENT ON COLUMN user_insurances.coverages IS
    'AES-256-GCM 암호화(봉투, JSON 배열 직렬화 후 통째 암호화). AAD=user_insurances:coverages';
