package com.soma.backend.domain.adjuster.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.entity.AdjusterRating;
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
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Profile profile,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Stats stats,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MonthlyActivity monthlyActivity,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Certification certification) {

  /**
   * 프로필 헤더 섹션. {@code nickname}은 USERS.nickname(사정사 프로필 name이 아님), {@code role}은
   * 인증 배지 판별용(CERTIFICATED_ADJUSTER=인증).
   *
   * <p>{@code email}은 항상 null이다 — V2__replace_email_with_phone.sql로 users.email 컬럼이 제거돼
   * (phone_number로 대체) 소스가 존재하지 않는다. 프론트가 필드 존재를 기대하므로 키는 유지하되 값만 null이며,
   * 이메일 재수집(회원가입/소셜 스코프 변경)은 별도 티켓이다.
   */
  public record Profile(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nickname,
      @Nullable @Schema(nullable = true) String email,
      @Nullable @Schema(nullable = true) String avatarUrl,
      @Nullable @Schema(nullable = true) String headline,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> specialties,
      @Nullable @Schema(nullable = true) Integer career,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String activityRegion,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role) {
  }

  /**
   * 누적 통계 섹션. {@code averageRating}·{@code consultationConversionRate}는 미집계 시 0.0/0이다
   * (홈 대시보드 #144와 일관 — 프론트는 review_count로 평점 유무를 판별).
   *
   * <p>{@code ReviewedReportListResponse.Stats}와 스키마 이름이 겹쳐 문서에는 검수 이력 쪽 필드로 나간다.
   * 그 응답 스키마가 문서에서 빠져 있어 지금은 프론트가 이 {@code Stats}로 검수 이력 타입을 맞추고 있으므로,
   * 이름 분리는 검수 이력 스키마 누락을 고칠 때 같이 한다.
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

  /**
   * 자격·등록 섹션. {@code createdAt}은 사정사 가입일(USERS.created_at).
   *
   * <p>{@code registrationNo}·{@code verifiedAt}은 사정사 상세({@code AdjusterDetailResponse.Certification})와
   * 필드명을 맞춘 것이다. 둘 다 미입력·미인증이면 null이다. 상세와 달리 등록번호를 ""로 바꾸지 않는다 — 프론트가
   * null일 때 "미등록"을 표시한다. {@code licenseNo}는 {@code registrationNo}와 같은 값이며 하위 호환을 위해 남겨 둔다.
   *
   * <p>스키마 이름을 따로 지정한다. 상세 응답의 {@code Certification}과 이름이 겹쳐 문서에 상세 쪽만 남았고,
   * 프론트 생성 zod가 그 스키마로 이 응답을 검증하다 실패했다.
   */
  @Schema(name = "AdjusterMyPageCertification")
  public record Certification(
      @Nullable
      @Schema(nullable = true, deprecated = true, description = "registration_no와 같은 값(하위 호환용)")
      String licenseNo,
      @Nullable @Schema(nullable = true, description = "등록/자격 번호(미입력이면 null)") String registrationNo,
      @Nullable @Schema(nullable = true, description = "자격 인증 시각(미인증이면 null)") LocalDateTime verifiedAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String activityRegion,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {
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
      long monthlyConsultConvertedCount,
      UnaryOperator<String> urlResolver) {
    AdjusterRating rating = AdjusterRating.of(profile.getRatingMean(), profile.getReviewCount());
    String activityRegion = RegionFormat.toSingle(profile.getActivityRegion());

    Profile profileSection = new Profile(
        user.getNickname(),
        null, // email: V2__replace_email_with_phone.sql로 users.email 제거됨 — 재수집 전까지 소스 없음(항상 null)
        urlResolver.apply(user.getAvatarUrl()),
        profile.getHeadline(),
        profile.getSpecialties() == null ? List.of() : profile.getSpecialties(),
        profile.getCareer(),
        activityRegion,
        user.getRole().name());

    Stats statsSection = new Stats(
        rating.average(), rating.reviewCount(), totalCompletedCount, consultationConversionRate);

    MonthlyActivity monthlyActivitySection =
        new MonthlyActivity(monthlyCompletedCount, monthlyConsultConvertedCount, rating.average());

    Certification certificationSection = new Certification(
        profile.getLicenseNo(),
        profile.getLicenseNo(),
        profile.getVerifiedAt(),
        activityRegion,
        user.getCreatedAt());

    return new AdjusterMyPageResponse(
        profileSection, statsSection, monthlyActivitySection, certificationSection);
  }
}
