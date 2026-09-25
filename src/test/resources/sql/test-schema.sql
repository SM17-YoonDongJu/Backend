-- 테스트 프로파일 전용 스키마 부트스트랩(spring.sql.init).
-- test 프로파일은 Flyway 비활성 + ddl-auto:create-drop이라 Hibernate가 엔티티 매핑만 만들고
-- 독립 시퀀스는 만들지 않는다. 사건번호 발급 native 쿼리(SELECT nextval('report_case_seq'))가
-- 참조하는 시퀀스를 여기서 만든다. 스키마 수식 없이 만들어 search_path의 public에 자리잡고,
-- 같은 이름의 비수식 nextval이 이를 해석한다(운영은 V47이 core에 만든다).
CREATE SEQUENCE IF NOT EXISTS report_case_seq;
