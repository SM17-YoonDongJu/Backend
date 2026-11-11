-- =====================================================================
-- V40 — public에 남은 앱 객체를 core로 이관해 환경 간 스키마 배치를 통일한다.
--
-- 배경: dev·prod의 앱 테이블은 이미 전부 core에 있고(#223 수습 과정에서 이관됨) public에는
-- flyway_schema_history만 남아 있다. 그런데 레포의 마이그레이션은 스키마를 명시하지 않아
-- public에 만든다(Flyway가 default-schema=public을 search_path 앞에 붙이기 때문). 즉 빈 DB에
-- 마이그레이션만 돌리면 운영과 다른 배치가 되고, 앱은 core를 보므로 ddl-auto=validate가
-- "missing table"로 실패해 기동하지 못한다(로컬 재현 확인).
--
-- 이 마이그레이션이 그 간극을 메운다. dev·prod에서는 옮길 대상이 없어 no-op이고, 신규 환경
-- (재해복구·신규 스테이징)에서만 실제로 이관한다. 재실행해도 안전하다(멱등).
--
-- SET SCHEMA는 대상 스키마(core)의 CREATE 권한만 요구하므로, public에 CREATE 권한이 없는
-- 환경(dev)에서도 통과한다. flyway_schema_history는 flyway.default-schema=public이 가리키는
-- 위치이므로 제외한다 — 옮기면 Flyway가 이력을 찾지 못한다.
--
-- 컬럼이 소유한 시퀀스(serial·identity)는 ALTER TABLE ... SET SCHEMA가 함께 옮기므로, 아래
-- 시퀀스 루프는 독립 시퀀스만 처리한다(현재는 없지만 신규 환경 대비).
-- =====================================================================
DO $$
DECLARE
  obj record;
BEGIN
  FOR obj IN
    SELECT tablename
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename <> 'flyway_schema_history'
  LOOP
    EXECUTE format('ALTER TABLE public.%I SET SCHEMA core', obj.tablename);
  END LOOP;

  FOR obj IN
    SELECT sequencename
    FROM pg_sequences
    WHERE schemaname = 'public'
  LOOP
    EXECUTE format('ALTER SEQUENCE public.%I SET SCHEMA core', obj.sequencename);
  END LOOP;
END $$;
