-- 사건번호(reports.case_no)를 앱이 더 이상 저장·발급하지 않는다(#413). 응답의 사건번호는 리포트 생성일과
-- 리포트 ID로 그때그때 만든다(CaseNo). 새 코드는 INSERT에 case_no를 넣지 않으므로 NOT NULL만 푼다.
--
-- 컬럼은 남긴다. 롤링 업데이트 중에는 구 파드가 이 컬럼을 읽고 쓰며 report_case_seq의 nextval을 부르고, 이전
-- 이미지로 롤백하면 ddl-auto: validate가 이 컬럼을 요구한다. 컬럼·시퀀스(report_case_seq)·옛 카운터 테이블
-- (report_case_sequences) DROP은 2차에서 한다. 그때까지 dev k6 시더가 시드 표식(K6-…)을 이 컬럼에 기록한다.
--
-- UNIQUE 제약은 그대로 둔다. PostgreSQL 기본(NULLS DISTINCT)이라 NULL인 행이 여러 개여도 충돌하지 않는다.
--
-- lock_timeout을 거는 이유: DROP NOT NULL은 카탈로그만 바꿔 바로 끝나지만 ACCESS EXCLUSIVE 잠금이 필요하다. 긴
-- 트랜잭션이 reports를 쥐고 있으면 그 잠금을 기다리는 동안 뒤따르는 reports 조회까지 모두 줄을 선다. 5초 안에
-- 잠금을 못 잡으면 이 마이그레이션이 실패해 새 파드만 기동에 실패하고, 구 파드는 계속 응답한다. SET LOCAL이라
-- 이 마이그레이션 트랜잭션에만 적용된다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE core.reports ALTER COLUMN case_no DROP NOT NULL;
