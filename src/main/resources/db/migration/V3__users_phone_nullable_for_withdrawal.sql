-- 회원 탈퇴 익명화 지원: phone_number의 NOT NULL 제약을 제거한다.
-- 탈퇴 시 phone_number를 NULL로 비워 UNIQUE 번호를 해제하면 동일 번호로 재가입할 수 있고,
-- 연락처(개인정보)도 파기된다. Postgres의 UNIQUE는 다중 NULL을 허용하므로 탈퇴 계정끼리
-- 충돌하지 않는다. 활성 계정은 가입 시 항상 번호를 채우므로 앱 레벨 불변식은 유지된다.
ALTER TABLE users ALTER COLUMN phone_number DROP NOT NULL;
