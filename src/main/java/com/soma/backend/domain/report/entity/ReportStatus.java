package com.soma.backend.domain.report.entity;

/**
 * REPORTS.status 값 객체. 정상 진행: AWAITING_INSPECTION → AWAITING_ADOPTION → COUNSELING → CLOSED.
 * NOT_SELECTED(미채택)는 접수 후 1주일 내에 상담 완료(CLOSED)되지 못했거나 검수를 하나도 받지 못한 리포트가
 * 스케줄러 스윕으로 도달하는 상태다 — 신규 사정사 검수는 차단(추가 접수 중단)되지만 진행 중인 상담/채팅은
 * 유지되고, 이후 상담이 잡히면 COUNSELING으로 재개될 수 있다(종료 상태 아님, CLOSED 직행은 없음).
 * BLOCKED는 AI 워커의 입력 가드레일(보험·법률 외 주제, PII 복호화 실패 등)이 접수를 차단했을 때 AI
 * 레포가 원시 SQL로 직접 기록하는 값이다(Backend 도메인 메서드를 거치지 않음) — 종료 상태로 취급한다.
 * 전이 허용 여부는 {@link Report#applyReviewTransition(ReportStatus)} 참고.
 */
public enum ReportStatus {
  AWAITING_INSPECTION,
  AWAITING_ADOPTION,
  COUNSELING,
  CLOSED,
  NOT_SELECTED,
  BLOCKED
}
