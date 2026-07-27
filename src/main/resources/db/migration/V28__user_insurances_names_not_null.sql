-- user_insurances의 insurer_name·product_name에 NOT NULL 제약을 추가한다.
-- 두 값은 마이페이지 "내 보험 정보" 카드의 필수 표시 항목(보험사명·상품명)이라 null을 허용하지 않는다.
-- 이 도메인은 아직 쓰기 경로(POST /users/me/insurances 미구현)가 없어 테이블이 비어 있으므로
-- 기존 데이터 백필 없이 제약만 추가한다(빈 테이블 SET NOT NULL은 즉시 완료).
ALTER TABLE user_insurances
    ALTER COLUMN insurer_name SET NOT NULL,
    ALTER COLUMN product_name SET NOT NULL;
