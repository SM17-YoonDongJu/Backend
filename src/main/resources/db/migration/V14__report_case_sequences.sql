-- 사건번호(case_no) 당일 시퀀스 발급기. 동시 생성 시 case_no UNIQUE 경합(→500)을 DB 레벨에서
-- 원천 차단한다(ReportHold의 ON CONFLICT 관례와 동일). day별 1행, seq를 원자적으로 증가시킨다.
CREATE TABLE report_case_sequences (
  day date PRIMARY KEY,
  seq integer NOT NULL
);
