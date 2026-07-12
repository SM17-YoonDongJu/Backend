-- 회원가입 정책 변경: 이메일 미수집, 이름(nickname)·생년월일·전화번호 수집.
-- email/email_verified 컬럼을 phone_number/phone_number_verified로 전환하고,
-- UNIQUE 제약을 nickname(이름, 중복 허용)에서 phone_number(한 번호 한 계정)로 옮긴다.

-- 이름(nickname): 실명은 중복 가능하므로 UNIQUE 제거, 가입 필수이므로 NOT NULL
ALTER TABLE users DROP CONSTRAINT users_nickname_key;
ALTER TABLE users ALTER COLUMN nickname SET NOT NULL;

-- email → phone_number 전환 + 한 번호 한 계정(UNIQUE) + 가입 필수(NOT NULL)
ALTER TABLE users RENAME COLUMN email TO phone_number;
ALTER TABLE users RENAME COLUMN email_verified TO phone_number_verified;
ALTER TABLE users ALTER COLUMN phone_number TYPE varchar(20);
ALTER TABLE users ALTER COLUMN phone_number SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_phone_number UNIQUE (phone_number);

-- 생년월일: 가입 필수
ALTER TABLE users ALTER COLUMN birth_date SET NOT NULL;
