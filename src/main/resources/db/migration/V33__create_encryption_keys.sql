-- =====================================================================
-- V33 — PII 봉투암호화(envelope encryption) 키 메타 테이블.
-- 평문 DEK는 절대 저장하지 않는다. wrapped_dek는 KMS CMK로 봉인된 ciphertext blob이라
-- RDS 스냅샷·덤프가 유출돼도 CMK 접근 없이는 복호화할 수 없다.
-- 앱은 기동 후 첫 암복호화 시점에 이 테이블을 읽어 kms:Decrypt로 평문 DEK를 풀고 메모리에만 캐시한다.
-- 로컬/개발/테스트는 raw key 경로(app.crypto.pii.key)를 쓰므로 이 테이블을 조회하지 않는다.
--
-- 접근 주체는 JPA가 아니라 JdbcTemplate DAO(infra/kms/EncryptionKeyStore)다 — 컨버터가 만들어지는
-- EntityManagerFactory 부트스트랩 도중에 읽어야 해서 Repository를 쓰면 순환이 생긴다(design.md §5.2).
-- =====================================================================
CREATE TABLE encryption_keys (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose     varchar(50)  NOT NULL,
    key_version int          NOT NULL,
    wrapped_dek bytea        NOT NULL,
    kms_key_id  varchar(512) NOT NULL,
    is_active   boolean      NOT NULL DEFAULT true,
    created_at  timestamp    NOT NULL DEFAULT now(),
    retired_at  timestamp
);

COMMENT ON TABLE encryption_keys IS 'PII 봉투암호화 DEK 메타 — wrapped(KMS ciphertext blob)만 저장, 평문 DEK 저장 금지';
COMMENT ON COLUMN encryption_keys.purpose IS '키 용도 구분. 현재 PII 한 종류(용도별 키 분리 원칙)';
COMMENT ON COLUMN encryption_keys.key_version IS '봉투 헤더에 기록되는 키 버전. 로테이션 시 +1';
COMMENT ON COLUMN encryption_keys.wrapped_dek IS 'kms:GenerateDataKey가 반환한 CiphertextBlob';
COMMENT ON COLUMN encryption_keys.kms_key_id IS '봉인에 사용한 CMK 식별자(키 ID 또는 ARN) — 감사·회전 추적용';
COMMENT ON COLUMN encryption_keys.retired_at IS '로테이션으로 비활성화된 시각. 활성 키는 NULL';

-- 봉투 헤더의 (purpose, key_version)으로 DEK를 유일하게 찾을 수 있어야 한다.
CREATE UNIQUE INDEX uk_encryption_keys_purpose_version ON encryption_keys (purpose, key_version);

-- 용도별 활성 키는 항상 1개 — 다중 인스턴스 동시 기동 시 최초 발급 경쟁을 DB가 흡수한다
-- (앱은 INSERT ... ON CONFLICT DO NOTHING 후 재조회한 행의 wrapped DEK를 쓴다).
CREATE UNIQUE INDEX uk_encryption_keys_active_purpose ON encryption_keys (purpose) WHERE is_active;
