package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * GET /reports/{reportId} 고객 상세용 크로스-애그리거트 읽기 모델(QueryDSL, native 대신). 리포트에 채택된 제안
 * (report_reviews.status=ACCEPTED)과 담당 사정사(users·adjuster_profiles)를 한 번에 조인해 확정값을 붙인다.
 *
 * <p>{@code acceptedReviewId}는 ACCEPTED 검수 존재 여부 마커다 — null이면 채택된 제안이 없어
 * {@code reviewComment}·{@code reviewedAt}·확정 배열 3종이 모두 null이고, 응답은 report 원본값으로 대체한다
 * (ReviewWorkspaceResponse의 started?review:report 규칙과 동일). 사정사 필드({@code adjusterNickname}·
 * {@code adjusterCareer})는 report.adjuster_id 기준 LEFT JOIN 결과이므로 담당 사정사가 없으면 null이다.
 */
public record CustomerReportDetailRow(
    UUID acceptedReviewId,
    String reviewComment,
    LocalDateTime reviewedAt,
    List<String> reviewGuarantees,
    List<String> reviewSpecial,
    List<String> reviewBasis,
    String adjusterNickname,
    Integer adjusterCareer) {
}
