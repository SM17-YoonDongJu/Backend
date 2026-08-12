-- =====================================================================
-- V37 — phone_number·provider_user_id HMAC 블라인드 인덱스 1단계(expand, 이슈 #232).
--
-- WHERE 동등비교(가입 중복확인·OAuth 로그인 조회)로 쓰이는 이 두 컬럼은 AES-GCM만으로는 조회할 수 없다
-- (매 암호화마다 nonce가 달라 같은 평문도 암호문이 매번 바뀐다). 그래서 실제 값은 AES-GCM 암호문으로,
-- 조회는 HMAC-SHA256 다이제스트(결정적)로 분리해서 저장한다.
--
-- 이 마이그레이션은 확장(expand) 단계만 수행한다 — 기존 평문 컬럼(phone_number·provider_user_id)은
-- 그대로 두고, 암호문·다이제스트를 담을 nullable 컬럼만 추가한다. 값 채우기(AES 암·복호화·HMAC 계산)는
-- SQL로 불가능하므로 애플리케이션(PiiHmacBackfillRunner)이 기동 시 채운다. 기존 평문 컬럼 삭제·rename·
-- UNIQUE 제약 이관은 백필 완료를 확인한 뒤 후속 마이그레이션(contract 단계)에서 수행한다.
--
-- 배포 중인 dev에 로그인 가능한 실제 계정(당시 users 12건·social_accounts 6건)이 있어, 대상 컬럼을
-- 지우고 재입력받는 대신 백필로 보존한다(design.md 9.2 expand-migrate-contract 패턴).
-- =====================================================================

ALTER TABLE users
    ADD COLUMN phone_number_enc  bytea,
    ADD COLUMN phone_number_hmac bytea;

COMMENT ON COLUMN users.phone_number_enc IS
    '[임시/expand] AES-256-GCM 암호화(봉투). AAD=users:phone_number. contract 단계에서 phone_number로 rename 예정';
COMMENT ON COLUMN users.phone_number_hmac IS
    '[임시/expand] HMAC-SHA256(phone_number) 조회용 다이제스트. contract 단계에서 UNIQUE 제약 추가 예정';

ALTER TABLE social_accounts
    ADD COLUMN provider_user_id_enc  bytea,
    ADD COLUMN provider_user_id_hmac bytea;

COMMENT ON COLUMN social_accounts.provider_user_id_enc IS
    '[임시/expand] AES-256-GCM 암호화(봉투). AAD=social_accounts:provider_user_id. contract 단계에서 provider_user_id로 rename 예정';
COMMENT ON COLUMN social_accounts.provider_user_id_hmac IS
    '[임시/expand] HMAC-SHA256(provider_user_id) 조회용 다이제스트. contract 단계에서 (provider, hmac) UNIQUE 제약 추가 예정';
