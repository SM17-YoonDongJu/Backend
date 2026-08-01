package com.soma.backend.domain.adjuster.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.global.common.RegionFormat;

/**
 * 손해사정사 마이페이지 조회 응답(GET /adjusters/me/mypage). 프론트 마이페이지 화면 계약에 맞춘 4개 섹션 —
 * {@code profile}(프로필 헤더), {@code stats}(누적 통계·전환율), {@code monthly_activity}(당월 활동),
 * {@code certification}(자격·등록). 필드는 Jackson 전역 설정으로 snake_case 직렬화된다
 * (average_rating, consultation_conversion_rate, monthly_activity 등).
 *
 * <p>애그리거트 간 참조는 객체가 아니라 ID(user_id)로만 넘나든다 — {@code AdjusterProfile}(adjuster)와
 * {@code User}(user)를 읽기 전용으로 조립한 크로스-애그리거트 뷰다.
 */
public record AdjusterMyPageResponse(
    Profile profile,
    Stats stats,
    MonthlyActivity monthlyActivity,
    Certification certification) {

  /**
   * 프로필 헤더 섹션. {@code nickname}은 USERS.nickname(사정사 프로필 name이 아님), {@code role}은
   * 인증 배지 판별용(CERTIFICATED_ADJUSTER=인증).
   *
   * <p>{@code email}은 항상 null이다 — V2__replace_email_with_phone.sql로 users.email 컬럼이 제거돼
   * (phone_number로 대체) 소스가 존재하지 않는다. 프론트가 필드 존재를 기대하므로 키는 유지하되 값만 null이며,
   * 이메일 재수집(회원가입/소셜 스코프 변경)은 별도 티켓이다.
   */
  public record Profile(
      String nickname,
      @Nullable String email,
      @Nullable String avatarUrl,
      @Nullable String headline,
      List<String> specialties,
      @Nullable Integer career,
      String activityRegion,
      String role) {
  }

  /**
   * 누적 통계 섹션. {@code averageRating}·{@code consultationConversionRate}는 미집계 시 0.0/0이다
   * (홈 대시보드 #144와 일관 — 프론트는 review_count로 평점 유무를 판별).
   */
  public record Stats(
      double averageRating,
      int reviewCount,
      long totalCompletedCount,
      int consultationConversionRate) {
  }

  /** 당월 활동 섹션 — 이번 달 검수 완료·상담 전환 건수(report_reviews 실시간 집계)와 평점(누적 재사용). */
  public record MonthlyActivity(
      long completedCount,
      long consultationConvertedCount,
      double averageRating) {
  }

  /** 자격·등록 섹션. {@code createdAt}은 사정사 가입일(USERS.created_at). */
  public record Certification(
      @Nullable String licenseNo,
      String activityRegion,
      LocalDateTime createdAt) {
  }

  /**
   * 조회 결과를 4개 섹션으로 조립한다. average_rating은 후기가 없으면(review_count가 null·0) 0.0을 내리고,
   * consultation_conversion_rate는 서비스에서 검수완료 0건이면 0으로 계산해 넘긴다(홈 대시보드 #144와 일관).
   * monthly_activity.average_rating은 당월 후기 원천이 없어 누적 rating_mean을 재사용한다.
   */
  public static AdjusterMyPageResponse from(
      AdjusterProfile profile,
      User user,
      long totalCompletedCount,
      int consultationConversionRate,
      long monthlyCompletedCount,
      long monthlyConsultConvertedCount) {
    Integer rawReviewCount = profile.getReviewCount();
    int reviewCount = rawReviewCount == null ? 0 : rawReviewCount;
    double averageRating = resolveAverageRating(rawReviewCount, profile);
    String activityRegion = RegionFormat.toSingle(profile.getActivityRegion());

    Profile profileSection = new Profile(
        user.getNickname(),
        null, // email: V2__replace_email_with_phone.sql로 users.email 제거됨 — 재수집 전까지 소스 없음(항상 null)
        user.getAvatarUrl(),
        profile.getHeadline(),
        profile.getSpecialties() == null ? List.of() : profile.getSpecialties(),
        profile.getCareer(),
        activityRegion,
        user.getRole().name());

    Stats statsSection =
        new Stats(averageRating, reviewCount, totalCompletedCount, consultationConversionRate);

    MonthlyActivity monthlyActivitySection =
        new MonthlyActivity(monthlyCompletedCount, monthlyConsultConvertedCount, averageRating);

    Certification certificationSection =
        new Certification(profile.getLicenseNo(), activityRegion, user.getCreatedAt());

    return new AdjusterMyPageResponse(
        profileSection, statsSection, monthlyActivitySection, certificationSection);
  }

  private static double resolveAverageRating(@Nullable Integer reviewCount, AdjusterProfile profile) {
    if (reviewCount == null || reviewCount == 0 || profile.getRatingMean() == null) {
      return 0.0;
    }
    return profile.getRatingMean().doubleValue();
  }
}
