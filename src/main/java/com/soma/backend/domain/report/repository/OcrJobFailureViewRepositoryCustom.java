package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;

/** {@code ai.ocr_job_failures} 동적/크로스-애그리거트 조회. native query 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface OcrJobFailureViewRepositoryCustom {

  /**
   * 실패 알림이 아직 나가지 않은 리포트를 찾는다(알림 스윕 전용 읽기 모델).
   *
   * <p>확정 실패(terminal=true) 행이 있고, {@code reports.analysis_failure_notified_at}이 NULL이며,
   * AI 초안이 아직 없는(= 성공하지 않은) 리포트를 <b>리포트 단위로 중복 없이</b> 반환한다.
   *
   * <p>실패 <i>행</i>이 아니라 <i>리포트</i>를 잘라 오는 이유: 행 단위로 limit을 걸면 한 리포트의 실패 행이
   * 배치 경계에서 쪼개져 대표 사유가 부분 집합으로 계산될 수 있다(예: masking_residual이 다음 배치로 밀리면
   * unreadable_file이 대표가 되어 잘못된 재업로드 안내가 나간다 — design.md §8 E5가 막으려는 바로 그 사고).
   * 여기서는 대상 리포트만 고르고, 대표 사유는 {@code findAllByReportIdInAndTerminalIsTrue}로 그 리포트의
   * 실패 행을 <b>전부</b> 읽어 계산한다.
   *
   * @param since {@code last_failed_at} 하한(lookback). 부분 인덱스 {@code (last_failed_at) WHERE terminal}를 탄다
   * @param limit 한 사이클 처리 상한(리포트 수)
   */
  List<PendingAnalysisFailureRow> findPendingNotification(LocalDateTime since, int limit);
}
