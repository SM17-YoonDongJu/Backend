package com.soma.backend.domain.adjuster.repository;

import java.math.BigDecimal;

/**
 * 홈 헤더·요약 카드용 사정사 비정규화 프로젝션(users LEFT JOIN adjuster_profiles).
 * name = adjuster_profiles.name ?: users.nickname. 누적 검수·상담·평점은 adjuster_profiles 비정규화 컬럼에서
 * 읽으며, 집계(검수 완료·상담·후기 write) 미구현 시 각 값은 null일 수 있다.
 */
public record AdjusterIdentityRow(
    String name,
    String avatarUrl,
    Integer casesReviewed,
    Integer completedConsultCount,
    BigDecimal ratingMean,
    Integer reviewCount) {
}
