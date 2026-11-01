package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;

/** {@code ai.report_job_failures} 크로스-애그리거트 조회. native query 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportJobFailureViewRepositoryCustom {

  /**
   * 리포트 생성 확정 실패 알림이 아직 나가지 않은 리포트를 찾는다(알림 스윕 전용 읽기 모델).
   *
   * <p>조건은 OCR 저널의 {@code findPendingNotification}과 같다 — {@code reports.analysis_failure_notified_at}이
   * NULL이고, AI 초안이 없고, 종료 상태(BLOCKED·NEEDS_REUPLOAD)가 아닌 리포트. 두 저널이 같은 멱등 가드를
   * 쓰므로 리포트당 FAILED 알림은 저널과 무관하게 1회다.
   *
   * @param since {@code last_failed_at} 하한(lookback). 부분 인덱스 {@code (last_failed_at) WHERE terminal}을 탄다
   * @param limit 한 사이클 처리 상한(리포트 수)
   */
  List<PendingAnalysisFailureRow> findPendingNotification(LocalDateTime since, int limit);
}
