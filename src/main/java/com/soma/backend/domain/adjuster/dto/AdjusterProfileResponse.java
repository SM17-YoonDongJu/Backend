package com.soma.backend.domain.adjuster.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.report.repository.AdjusterReviewRow;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.global.common.RegionFormat;

/**
 * 손해사정사 프로필 조회 응답(GET /adjusters/me/profile). 프로필 수정 화면 초기값용 flat 구조 —
 * 프로필(닉네임·헤드라인·소개·전문분야·경력)과 누적 평점·후기, 최근 후기 2건을 한 번에 내린다.
 * 필드는 Jackson 전역 설정으로 snake_case 직렬화된다(adjuster_id, avatar_url, activity_region 등).
 *
 * <p>애그리거트 간 참조는 객체가 아니라 ID(user_id)로만 넘나든다 — {@code AdjusterProfile}(adjuster)와
 * {@code User}(user), {@code AdjusterReviewRow}(report)를 읽기 전용으로 조립한 크로스-애그리거트 뷰다.
 */
public record AdjusterProfileResponse(
    UUID adjusterId,
    String nickname,
    String headline,
    @Nullable String avatarUrl,
    String activityRegion,
    String introduction,
    List<String> specialties,
    List<CareerItem> careers,
    Integer career,
    double averageRating,
    int reviewCount,
    List<RecentReview> recentReviews,
    int completedConsultCount,
    long handledCaseCount,
    int pendingReviewCount,
    LocalDateTime updatedAt) {

  /** 주요 경력 항목(careers jsonb 요소). */
  public record CareerItem(String period, String company) {
  }

  /** 최근 후기 항목. item은 연결 리포트의 사건 유형 영문 값(없으면 null). */
  public record RecentReview(
      String nickname,
      int score,
      @Nullable String item,
      LocalDateTime reviewedAt,
      @Nullable String content) {
  }

  /**
   * 조회 결과를 응답으로 조립한다. 배열 필드는 null-safe로 빈 배열 처리하고, average_rating은 후기가 없으면
   * (review_count가 null·0) 신규 사정사로 보아 0.0을 내린다(홈 대시보드 #144와 일관 — 프론트는
   * review_count로 평점 유무를 판별).
   *
   * <p>{@code pendingReviewCount}는 검수 대기 건수(파트너 헤더·인사말 "검수 대기 N건"용)로, = 홈 대시보드
   * summary.pending_count (countPendingPool: AWAITING_INSPECTION + AWAITING_ADOPTION, BE #122)다 —
   * 서비스가 계산해 넘긴다.
   */
  public static AdjusterProfileResponse from(
      AdjusterProfile profile, User user, List<AdjusterReviewRow> recent,
      long handledCaseCount, int pendingReviewCount) {
    Integer rawReviewCount = profile.getReviewCount();
    int reviewCount = rawReviewCount == null ? 0 : rawReviewCount;
    double averageRating = resolveAverageRating(rawReviewCount, profile);

    return new AdjusterProfileResponse(
        profile.getUserId(),
        user.getNickname(),
        orEmpty(profile.getHeadline()),
        user.getAvatarUrl(),
        RegionFormat.toSingle(profile.getActivityRegion()),
        orEmpty(profile.getIntroduction()),
        profile.getSpecialties() == null ? List.of() : profile.getSpecialties(),
        toCareerItems(profile.getCareers()),
        profile.getCareer() == null ? 0 : profile.getCareer(),
        averageRating,
        reviewCount,
        toRecentReviews(recent),
        profile.getCompletedConsultCount() == null ? 0 : profile.getCompletedConsultCount(),
        handledCaseCount,
        pendingReviewCount,
        profile.getUpdatedAt() != null ? profile.getUpdatedAt() : profile.getCreatedAt());
  }

  private static double resolveAverageRating(@Nullable Integer reviewCount, AdjusterProfile profile) {
    if (reviewCount == null || reviewCount == 0 || profile.getRatingMean() == null) {
      return 0.0;
    }
    return profile.getRatingMean().doubleValue();
  }

  private static List<CareerItem> toCareerItems(@Nullable List<AdjusterProfile.Career> careers) {
    if (careers == null) {
      return List.of();
    }
    return careers.stream()
        .map(career -> new CareerItem(orEmpty(career.period()), orEmpty(career.company())))
        .toList();
  }

  private static List<RecentReview> toRecentReviews(List<AdjusterReviewRow> rows) {
    return rows.stream()
        .map(row -> new RecentReview(
            row.nickname(),
            row.score() == null ? 0 : row.score(),
            row.accidentType() == null ? null : row.accidentType().getValue(),
            row.createdAt(),
            row.review()))
        .toList();
  }

  private static String orEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }
}
