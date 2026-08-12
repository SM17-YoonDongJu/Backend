-- =====================================================================
-- V38 — phone_number·provider_user_id HMAC 블라인드 인덱스 2단계(contract, 이슈 #232).
--
-- V37(expand)이 추가한 *_enc/*_hmac 컬럼을 PiiHmacIndexBackfillRunner가 채운 뒤 배포하는 마이그레이션이다.
-- 옛 평문 컬럼(phone_number·provider_user_id)을 지우고, 암호문 컬럼을 최종 이름으로 되돌리며(엔티티가
-- 이 이름으로 매핑한다), HMAC 컬럼에 조회·유일성 제약을 건다.
--
-- ⚠️ 가드: 백필이 끝나지 않은 상태(평문은 있는데 enc가 비어 있는 행)에서 적용하면 그 행의 값을
--    조용히 잃는다 — 배포 전 PiiHmacIndexBackfillRunner가 실행돼 누락 0건인지 반드시 확인한다.
-- =====================================================================

DO $$
DECLARE
    missing_rows bigint;
BEGIN
    SELECT count(*) INTO missing_rows FROM users
    WHERE phone_number IS NOT NULL AND phone_number_enc IS NULL;
    IF missing_rows > 0 THEN
        RAISE EXCEPTION
            'users에 phone_number_enc 백필이 안 된 행이 %건 있습니다 — PiiHmacIndexBackfillRunner를 먼저 실행하세요',
            missing_rows;
    END IF;

    SELECT count(*) INTO missing_rows FROM social_accounts
    WHERE provider_user_id IS NOT NULL AND provider_user_id_enc IS NULL;
    IF missing_rows > 0 THEN
        RAISE EXCEPTION
            'social_accounts에 provider_user_id_enc 백필이 안 된 행이 %건 있습니다 — PiiHmacIndexBackfillRunner를 먼저 실행하세요',
            missing_rows;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT uk_users_phone_number;
ALTER TABLE users DROP COLUMN phone_number;
ALTER TABLE users RENAME COLUMN phone_number_enc TO phone_number;
ALTER TABLE users ADD CONSTRAINT uk_users_phone_number_hmac UNIQUE (phone_number_hmac);

COMMENT ON COLUMN users.phone_number IS
    'AES-256-GCM 암호화(봉투). AAD=users:phone_number. 앱 컨버터 전용 — SQL 직접 조회 금지';
COMMENT ON COLUMN users.phone_number_hmac IS
    'HMAC-SHA256(phone_number) 블라인드 인덱스 — 조회·유일성은 이 컬럼으로 한다';

-- ---------------------------------------------------------------------
-- social_accounts
-- ---------------------------------------------------------------------
ALTER TABLE social_accounts DROP CONSTRAINT uq_social_accounts_provider_user;
ALTER TABLE social_accounts DROP COLUMN provider_user_id;
ALTER TABLE social_accounts RENAME COLUMN provider_user_id_enc TO provider_user_id;
ALTER TABLE social_accounts ALTER COLUMN provider_user_id SET NOT NULL;
ALTER TABLE social_accounts ALTER COLUMN provider_user_id_hmac SET NOT NULL;
ALTER TABLE social_accounts
    ADD CONSTRAINT uq_social_accounts_provider_user_hmac UNIQUE (provider, provider_user_id_hmac);

COMMENT ON COLUMN social_accounts.provider_user_id IS
    'AES-256-GCM 암호화(봉투). AAD=social_accounts:provider_user_id. 앱 컨버터 전용 — SQL 직접 조회 금지';
COMMENT ON COLUMN social_accounts.provider_user_id_hmac IS
    'HMAC-SHA256(provider_user_id) 블라인드 인덱스 — 조회·유일성은 (provider, 이 컬럼)으로 한다';
