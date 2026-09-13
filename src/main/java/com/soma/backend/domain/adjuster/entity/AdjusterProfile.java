package com.soma.backend.domain.adjuster.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * ADJUSTER_PROFILES Aggregate Root — 손해사정사 프로필(자격·전문분야·비정규화 집계).
 *
 * <p>USERS와는 user_id(UUID)로만 연결한다(1:1, user_id UK). 상담·평점(completed_consult_count·
 * rating_mean·review_count)은 비정규화 컬럼이다 — 상담 완료 수는 상담 수락(담당 확정) 시,
 * 평점은 평가 등록 시 각각 원천 테이블(report_reviews·adjuster_reviews) 전량 재집계로 갱신한다
 * ({@link #refreshCompletedConsultCount}·{@link #refreshRating}). 증분이 아니라 재집계라 같은 입력에
 * 대해 멱등이고, 값이 어긋나도 다음 이벤트에서 스스로 복구된다. 백필(V46) 이전에 만들어진 행은 아직
 * null일 수 있으며 그때의 표시 규칙은 {@link AdjusterRating}가 정한다. 검수 완료 건수는 비정규화하지
 * 않는다 — report_reviews를 매번 실시간 집계해 쓴다(이중 계상 위험을 없앤다).
 */
@Entity
@Table(name = "adjuster_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterProfile extends BaseEntity {

  /** rating_mean numeric은 정밀도 미지정이라 AVG의 무한소수가 그대로 박힌다 — 저장 scale을 여기서 고정한다. */
  private static final int RATING_SCALE = 2;

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Column(name = "license_no", length = 100)
  private String licenseNo;

  @Column(name = "name", length = 100)
  private String name;

  @Column(name = "headline", length = 100)
  private String headline;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "specialties", columnDefinition = "text[]")
  private List<String> specialties;

  @Column(name = "career")
  private Integer career;

  @Column(name = "completed_consult_count")
  private Integer completedConsultCount;

  @Column(name = "rating_mean")
  private BigDecimal ratingMean;

  @Column(name = "review_count")
  private Integer reviewCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "careers", columnDefinition = "jsonb")
  private List<Career> careers;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "consult_methods", columnDefinition = "text[]")
  private List<String> consultMethods;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "activity_region", columnDefinition = "text[]")
  private List<String> activityRegion;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @Column(name = "introduction")
  private String introduction;

  @Column(name = "registration_url")
  private String registrationUrl;

  /**
   * 프로필(태그라인·소개·경력연차·활동지역·전문분야·주요경력)을 부분 수정한다. {@code null} 인자는
   * 변경하지 않는다(부분 수정). 프로필 사진(avatar_url)은 USERS 소관이라 여기서 다루지 않는다.
   */
  public void updateProfile(
      @Nullable String headline, @Nullable String introduction, @Nullable Integer career,
      @Nullable List<String> activityRegion, @Nullable List<String> specialties,
      @Nullable List<Career> careers) {
    if (headline != null) {
      this.headline = headline;
    }
    if (introduction != null) {
      this.introduction = introduction;
    }
    if (career != null) {
      this.career = career;
    }
    if (activityRegion != null) {
      this.activityRegion = activityRegion;
    }
    if (specialties != null) {
      this.specialties = specialties;
    }
    if (careers != null) {
      this.careers = careers;
    }
  }

  /**
   * 평점 비정규화 컬럼(rating_mean·review_count)을 재집계 결과로 덮어쓴다. 인자는 증분이 아니라
   * adjuster_reviews 전량 집계값이라 같은 입력에 대해 멱등이다.
   *
   * <p>불변식: 건수는 음수일 수 없다. {@code reviewCount}가 0이면 {@code ratingMean}을 null로 강제해
   * "후기 0건 = 평점 없음"을 지키고({@link AdjusterRating#of} 폴백 규칙과 정합), 0보다 크면
   * {@code ratingMean}이 null이어선 안 된다. 저장 평균은 scale 2(HALF_UP)로 고정해 백필(V46) 값과
   * 런타임 값이 같은 표현을 갖게 한다.
   */
  public void refreshRating(@Nullable BigDecimal ratingMean, int reviewCount) {
    if (reviewCount < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
    if (reviewCount == 0) {
      this.ratingMean = null;
      this.reviewCount = 0;
      return;
    }
    if (ratingMean == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
    this.ratingMean = ratingMean.setScale(RATING_SCALE, RoundingMode.HALF_UP);
    this.reviewCount = reviewCount;
  }

  /**
   * 상담 완료 수(completed_consult_count)를 재집계 결과로 덮어쓴다 — 사용자가 최종 채택해 담당이 확정된
   * 제안(report_reviews.status = ACCEPTED) 건수다. 음수는 허용하지 않는다.
   */
  public void refreshCompletedConsultCount(int completedConsultCount) {
    if (completedConsultCount < 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
    this.completedConsultCount = completedConsultCount;
  }

  /** 주요 경력 항목(careers jsonb 요소) — {@code [{period, company}]}. */
  public record Career(String period, String company) {
  }
}
