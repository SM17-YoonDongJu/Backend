-- 성별(gender)을 가입 필수 컬럼으로 승격한다.
-- 기존 NULL 유저는 빈 문자열로 백필한 뒤 NOT NULL 제약을 건다.
-- 정책: 값은 반드시 존재해야 하되(NOT NULL) 빈 문자열은 허용한다("필수 입력, 빈칸 허용").
UPDATE users SET gender = '' WHERE gender IS NULL;
ALTER TABLE users ALTER COLUMN gender SET NOT NULL;
