package com.soma.backend.domain.adjuster.repository;

import java.math.BigDecimal;

/**
 * 홈 헤더·요약 카드용 사정사 비정규화 프로젝션(users LEFT JOIN adjuster_profiles).
 * name = adjuster_profiles.name ?: users.nickname. 상담 전환·평점은 adjuster_profiles 비정규화 컬럼에서 읽으며,
 * 집계(상담·후기 write) 미구현 시 각 값은 null일 수 있다. 완료 누적은 여기서 읽지 않고 report_reviews 실시간 집계다.
 */
public record AdjusterIdentityRow(
    String name,
    String avatarUrl,
    Integer completedConsultCount,
    BigDecimal ratingMean,
    Integer reviewCount) {
}
