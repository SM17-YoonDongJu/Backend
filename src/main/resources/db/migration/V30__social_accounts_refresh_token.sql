-- 제공자 refresh_token을 저장할 컬럼을 추가한다(Apple 탈퇴 revoke용).
-- 값은 항상 AES-GCM 암호문(base64)만 저장하며 평문은 넣지 않는다. Apple 외 provider는 null이라 nullable.
-- 엔티티의 @Column(length = 1024)와 정합되게 varchar(1024)로 둔다(ddl-auto=validate 통과).
ALTER TABLE social_accounts
    ADD COLUMN refresh_token varchar(1024);
