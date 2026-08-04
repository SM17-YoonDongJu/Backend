package com.soma.backend.domain.adjuster.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.report.repository.AdjusterReviewRow;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.global.common.RegionFormat;

/**
 * 손해사정사 공개 상세 조회 응답(GET /adjusters/{adjusterId}). 고객 앱이 임의 사정사의 공개 프로필을
 * 열람하는 뷰로, {@code /adjusters/me/profile}(본인)의 공개 변형이다 — self 전용 필드
 * (pending_review_count·updated_at)는 제외하고 상담 안내(consult_guide)·자격 인증(certification)
 * 블록을 더한다. 필드는 Jackson 전역 설정으로 snake_case 직렬화된다(adjuster_id, avatar_url, consult_guide 등).
 *
 * <p>애그리거트 간 참조는 객체가 아니라 ID(user_id)로만 넘나든다 — {@code AdjusterProfile}(adjuster)와
 * {@code User}(user), {@code AdjusterReviewRow}(report)를 읽기 전용으로 조립한 크로스-애그리거트 뷰다.
 * 공개 응답이라 계약에 명시된 필드만 노출하고 면허 원본 등 불필요한 PII는 싣지 않는다.
 */
public record AdjusterDetailResponse(
    UUID adjusterId,
    String nickname,
    @Nullable String avatarUrl,
    String headline,
    String activityRegion,
    String introduction,
    List<String> specialties,
    List<CareerItem> careers,
    int career,
    double averageRating,
    int reviewCount,
    List<RecentReview> recentReviews,
    int completedConsultCount,
    long handledCaseCount,
    boolean verified,
    ConsultGuide consultGuide,
    Certification certification) {

  /** 주요 경력 항목(careers jsonb 요소). 공개 상세에서는 원소의 null 필드를 ""로 채운다(전부 비null). */
  public record CareerItem(String period, String company) {
  }

  /** 최근 후기 항목. 공개 상세에서는 item(연결 리포트 사건 유형)·content 포함 전부 비null(빈 값은 ""). */
  public record RecentReview(
      String nickname,
      int score,
      String item,
      LocalDateTime reviewedAt,
      String content) {
  }

  /**
   * 상담 안내 블록. {@code method}는 상담 방식(consult_methods)을 합친 문자열(없으면 "").
   *
   * <p>{@code initialConsult}·{@code feeBasis}는 아직 BE 데이터 소스가 없어 빈 문자열 placeholder다
   * (FE도 실 API 미정으로 MSW 선제공 상태) — 소스가 정해지면 채운다.
   */
  public record ConsultGuide(String method, String initialConsult, String feeBasis) {
  }

  /**
   * 자격 인증 블록. {@code registrationNo}는 등록/자격 번호(license_no, 없으면 ""),
   * {@code verifiedAt}은 인증 시각(데이터가 없으면 null).
   */
  public record Certification(String registrationNo, @Nullable LocalDateTime verifiedAt) {
  }

  /**
   * 조회 결과를 공개 상세 응답으로 조립한다. 계약상 비null 필드는 ""·0·[]로 coalesce하고,
   * average_rating은 후기가 없으면(review_count가 null·0) 신규 사정사로 보아 0.0을 내린다
   * ({@code /adjusters/me/profile}·홈 대시보드 #144와 동일 — 프론트는 review_count로 평점 유무를 판별).
   * {@code verified}는 자격 인증 사정사 여부로, RBAC 권한(Role.CERTIFICATED_ADJUSTER)을 진실의 원천으로 쓴다.
   */
  public static AdjusterDetailResponse from(
      AdjusterProfile profile, User user, List<AdjusterReviewRow> recent, long handledCaseCount,
      UnaryOperator<String> urlResolver) {
    Integer rawReviewCount = profile.getReviewCount();
    int reviewCount = rawReviewCount == null ? 0 : rawReviewCount;
    double averageRating = resolveAverageRating(rawReviewCount, profile);

    return new AdjusterDetailResponse(
        profile.getUserId(),
        user.getNickname(),
        urlResolver.apply(user.getAvatarUrl()),
        profile.getHeadline() == null ? "" : profile.getHeadline(),
        RegionFormat.toSingle(profile.getActivityRegion()),
        profile.getIntroduction() == null ? "" : profile.getIntroduction(),
        profile.getSpecialties() == null ? List.of() : profile.getSpecialties(),
        toCareerItems(profile.getCareers()),
        profile.getCareer() == null ? 0 : profile.getCareer(),
        averageRating,
        reviewCount,
        toRecentReviews(recent),
        profile.getCompletedConsultCount() == null ? 0 : profile.getCompletedConsultCount(),
        handledCaseCount,
        user.getRole() == Role.CERTIFICATED_ADJUSTER,
        toConsultGuide(profile.getConsultMethods()),
        new Certification(
            profile.getLicenseNo() == null ? "" : profile.getLicenseNo(), profile.getVerifiedAt()));
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
        .map(career -> new CareerItem(
            career.period() == null ? "" : career.period(),
            career.company() == null ? "" : career.company()))
        .toList();
  }

  private static List<RecentReview> toRecentReviews(List<AdjusterReviewRow> rows) {
    return rows.stream()
        .map(row -> new RecentReview(
            row.nickname(),
            row.score() == null ? 0 : row.score(),
            row.accidentType() == null ? "" : row.accidentType().getValue(),
            row.createdAt(),
            row.review() == null ? "" : row.review()))
        .toList();
  }

  /** consult_methods(text[])를 ", "로 합쳐 상담 방식 문자열로 만든다. null/빈 값은 "". */
  private static ConsultGuide toConsultGuide(@Nullable List<String> consultMethods) {
    String method = consultMethods == null || consultMethods.isEmpty()
        ? ""
        : String.join(", ", consultMethods);
    // initial_consult·fee_basis: BE 데이터 소스 미정(FE도 MSW 선제공) — 빈 문자열 placeholder로 채운다.
    return new ConsultGuide(method, "", "");
  }
}
