-- (provider, provider_user_id) 조합에 UNIQUE 제약을 추가한다.
-- 로그인 식별키(제공자 + 제공자 사용자 ID)의 중복 계정을 DB 레벨에서 차단한다(기존 unique 부재 보강).
-- 신규 스키마라 사전 중복행이 없다고 가정한다(중복이 있으면 이 마이그레이션이 실패해 조기 발견된다).
ALTER TABLE social_accounts
    ADD CONSTRAINT uq_social_accounts_provider_user UNIQUE (provider, provider_user_id);
