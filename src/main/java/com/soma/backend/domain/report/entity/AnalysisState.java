package com.soma.backend.domain.report.entity;

/**
 * 리포트 분석 파이프라인(OCR → AI 초안) 처리 상태. REPORTS.status(검수·매칭 생명주기)와는 **다른 축**이며,
 * DB에 저장하지 않는 파생값이다 — 두 terminal 실패 저널({@code ai.ocr_job_failures}·
 * {@code ai.report_job_failures})과 AI 초안 존재 여부에서 매 조회 시점에 계산한다(design.md §5-1).
 *
 * <p>저장하지 않는 이유: AI 워커가 실패를 회복하면 실패 행을 지우는데(clear_job_failure), 상태를 스냅샷으로
 * 들고 있으면 그 회복 전이를 놓쳐 사용자에게 영원히 실패로 남는다(design.md §8 E2).
 */
public enum AnalysisState {

  /** OCR·AI 분석 진행 중. 일시 실패(terminal=false) 행이 있어도 여기에 포함된다(사용자에게 노출하지 않는다). */
  PROCESSING,

  /** AI 초안 생성 완료. 실패 행이 남아 있어도 성공이 이긴다(design.md §8 E3). */
  COMPLETED,

  /**
   * 확정 실패(terminal=true) 존재 — OCR 단계(결정적 실패 또는 재전달 한도 소진)거나 리포트 생성 단계(재전달
   * 한도 소진)다. 워커가 회복하면 저널 행이 지워져 다른 상태로 바뀔 수 있다.
   */
  FAILED,

  /**
   * {@link ReportStatus#BLOCKED}(AI 입력 가드레일 차단 또는 리포트 생성 LLM 거절). 가드레일은 OCR 이전에
   * 파이프라인을 끊고 LLM 거절은 저널에 기록되지 않아서, 어느 쪽이든 실패 저널에 행이 남지 않으므로 이 신호만은
   * 저널이 아니라 {@code reports.status}에서 직접 판정한다(REPORTS.status 축 위반이지만, 그러지 않으면 이
   * 상태가 PROCESSING으로 영원히 오표시된다).
   */
  BLOCKED,

  /**
   * {@link ReportStatus#NEEDS_REUPLOAD}(OCR 품질 미달 — 문서를 다시 올려야 진행된다). 실패가 아니라 품질
   * 판정이라 {@code ai.ocr_job_failures}에 행이 남지 않으므로, {@code BLOCKED}와 같은 예외로 저널이 아니라
   * {@code reports.status}에서 직접 판정한다.
   *
   * <p>{@link #FAILED}로 뭉치지 않는다 — FAILED는 계약상 대표 사유(failure_reason)와 실패 시각이 non-null인데
   * 둘 다 저널 파생값이라 채울 수 없고, 무엇보다 사용자 액션이 정반대다(FAILED의 대표 사유는 대부분 재업로드가
   * 무의미한 HOLD·NOT_SUPPORTED인 반면 이 상태는 재업로드가 유일한 해결책이다).
   *
   * <p>이 상태도 사유 코드를 가질 수 있다 — 저널이 아니라 {@code ai.claim_readiness.reupload_reason}(AI 013)에서
   * 오며 별도 ACL {@link ReuploadReason}이 번역한다(확인되지 않으면 null). 실패 시각은 여전히 null이다.
   */
  NEEDS_REUPLOAD
}
