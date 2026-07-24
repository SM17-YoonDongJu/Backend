package com.soma.backend.domain.adjuster.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;

/**
 * 본인 프로필 조회 응답(GET /adjusters/me/mypage). 파트너 헤더·인사말용 요약 + 검수 대기 수.
 * 필드는 Jackson 전역 설정으로 snake_case 직렬화된다(average_rating, review_count, pending_review_count 등).
 */
public record AdjusterMyProfileResponse(
    UUID adjusterId,
    @Nullable String name,
    @Nullable String headline,
    List<String> specialties,
    @Nullable Integer career,
    @Nullable BigDecimal averageRating,
    int reviewCount,
    int casesReviewed,
    int completedConsultCount,
    List<String> activityRegion,
    List<String> consultMethods,
    @Nullable String introduction,
    long pendingReviewCount) {

  public static AdjusterMyProfileResponse from(AdjusterProfile profile, long pendingReviewCount) {
    return new AdjusterMyProfileResponse(
        profile.getUserId(),
        profile.getName(),
        profile.getHeadline(),
        profile.getSpecialties() == null ? List.of() : profile.getSpecialties(),
        profile.getCareer(),
        profile.getRatingMean(),
        profile.getReviewCount() == null ? 0 : profile.getReviewCount(),
        profile.getCasesReviewed() == null ? 0 : profile.getCasesReviewed(),
        profile.getCompletedConsultCount() == null ? 0 : profile.getCompletedConsultCount(),
        profile.getActivityRegion() == null ? List.of() : profile.getActivityRegion(),
        profile.getConsultMethods() == null ? List.of() : profile.getConsultMethods(),
        profile.getIntroduction(),
        pendingReviewCount);
  }
}
