package com.soma.backend.domain.adjuster.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.user.entity.Role;

/**
 * 손해사정사 목록 카드 프로젝션(QueryDSL). adjuster_profiles를 users와 user_id로 조인해 뽑은 읽기 전용 행이다.
 * 비정규화 집계(rating_mean·review_count·career·completed_consult_count)는 아직 write 경로가 없어 null일 수
 * 있으므로 응답 조립 시 coalesce한다. verified 판정을 위해 users.role을 그대로 싣는다(닉네임·아바타도 users에서).
 */
public record AdjusterCardRow(
    UUID adjusterId,
    String nickname,
    @Nullable String avatarUrl,
    Role role,
    @Nullable List<String> specialties,
    @Nullable String headline,
    @Nullable BigDecimal ratingMean,
    @Nullable Integer reviewCount,
    @Nullable Integer career,
    @Nullable Integer completedConsultCount,
    @Nullable List<String> activityRegion) {
}
