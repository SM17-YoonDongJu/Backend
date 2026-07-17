package com.soma.backend.domain.report.dto;

/**
 * GET /users/me/activity-summary 응답 — 고객 마이페이지 사이드바·모바일 스탯의 활동 카운트(FE #105).
 *
 * <p>모두 요청 사용자(리포트 소유자) 기준 집계다. 퍼널 순서(리포트→제안→상담→종결)로 읽는다:
 * {@code reportCount} 내가 만든 리포트 수(전체 상태), {@code proposalCount} 내 리포트에 달린 제안
 * (REPORT_REVIEWS) 수(거절 포함 — 받은 제안 누적), {@code consultCount} 그중 상담 전환(REVIEW status
 * COUNSELING) 수(채팅 상담 플로우 구현 전까지는 0), {@code closedCount} 종결(REPORTS.status = CLOSED)된
 * 내 리포트 수. 필드는 Jackson 전역 설정에 따라 snake_case로 직렬화된다(report_count 등).
 */
public record UserActivitySummaryResponse(
    long reportCount, long proposalCount, long consultCount, long closedCount) {
}
