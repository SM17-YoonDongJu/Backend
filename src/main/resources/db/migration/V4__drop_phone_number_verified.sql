-- 핸드폰 인증 기능 폐기: phone_number_verified 컬럼을 제거한다.
-- 더 이상 전화번호 인증 상태를 추적하지 않으므로 컬럼 자체를 드롭한다.
ALTER TABLE users DROP COLUMN phone_number_verified;
