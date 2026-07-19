package com.soma.backend.domain.report.dto;

/**
 * API#1 응답.
 *
 * <p>{@code pendingCount}·{@code dueSoonCount}는 검수 대기 풀(전역, REPORTS.status 기준) 카운트이고,
 * {@code inProgressCount}는 요청 사정사 본인의 진행 중 검수(REPORT_REVIEWS.status = SENT·COUNSELING)
 * 카운트다 — "진행 중 검수" 요약 카드용(FE #94). 축이 달라 전역·사정사별 값이 한 응답에 공존한다.
 */
public record PendingReviewSummaryResponse(long pendingCount, long dueSoonCount, long inProgressCount) {
}
