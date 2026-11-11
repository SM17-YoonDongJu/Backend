-- 아이디·비밀번호 일반 로그인(이슈 #389)용 자격증명 테이블.
-- 소셜 로그인(social_accounts)과 같은 방식으로 users(프로필)와 분리된 Aggregate로 두고 user_id(UUID)로만 잇는다
-- — 자격증명은 auth 컨텍스트 소유라 users 테이블에 컬럼을 덧붙이지 않는다.
-- 스키마를 core.로 명시한다: Flyway는 default-schema가 public이라 미지정 CREATE TABLE이 public에 생기는데
-- 앱(Hibernate)의 기본 스키마는 core라서 ddl-auto: validate가 테이블을 못 찾고 기동이 깨진다(이슈 #223·#244).
-- user_id         — UNIQUE로 한 회원당 자격증명 하나를 보장하고, FK는 social_accounts·device_tokens와 같게 걸어
--                   고아 자격증명을 DB가 막는다(Aggregate 간 참조를 ID로만 두는 건 코드 규칙이고 DB 참조
--                   정합성은 별개로 챙긴다). 탈퇴가 soft delete라 ON DELETE 절은 두지 않는다. 참조 대상을
--                   core.users로 수식한다 — 마이그레이션의 search_path는 public이 앞이라 비수식 이름의
--                   해석이 환경에 따라 달라질 수 있다.
--                   UNIQUE가 인덱스를 만들어 주므로 social_accounts처럼 별도 인덱스를 두지 않는다.
-- login_id UNIQUE — 아이디 중복 방지. 서비스가 소문자로 정규화해 저장·조회하므로 대소문자만 다른 중복 계정이
--                   생기지 않는다(DB 함수 인덱스 대신 애플리케이션 정규화로 맞춘다).
-- password_hash   — BCrypt 해시만 보관한다(평문은 어디에도 남기지 않는다). BCrypt는 60자지만 알고리즘 교체
--                   여지를 두고 100자로 잡았다.
-- created_at/updated_at은 BaseEntity(JPA Auditing)가 채운다. updated_at은 BaseEntity에서 nullable이라
-- DB도 nullable로 둔다(최초 삽입 시 값이 없는 상태를 그대로 허용).
CREATE TABLE core.password_credentials (
    id            uuid PRIMARY KEY,
    user_id       uuid         NOT NULL UNIQUE REFERENCES core.users (id),
    login_id      varchar(30)  NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    created_at    timestamp    NOT NULL,
    updated_at    timestamp
);
