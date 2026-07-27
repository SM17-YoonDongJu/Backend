package com.soma.backend.domain.adjuster.entity;

import java.math.BigDecimal;
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

/**
 * ADJUSTER_PROFILES Aggregate Root — 손해사정사 프로필(자격·전문분야·비정규화 집계).
 *
 * <p>USERS와는 user_id(UUID)로만 연결한다(1:1, user_id UK). 누적 검수·상담·평점은 비정규화 컬럼이며
 * 갱신 책임은 검수 완료·상담·후기 write 로직에 있다(현재 미구현이라 null일 수 있음). 지금은 홈 대시보드
 * 조회에서 읽기 전용으로 쓰인다(도메인 성숙 시 자격 신청 승인·프로필 수정 등 write 유스케이스가 붙는다).
 */
@Entity
@Table(name = "adjuster_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterProfile extends BaseEntity {

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

  @Column(name = "cases_accepted")
  private Integer casesAccepted;

  @Column(name = "cases_reviewed")
  private Integer casesReviewed;

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

  /** 주요 경력 항목(careers jsonb 요소) — {@code [{period, company}]}. */
  public record Career(String period, String company) {
  }
}
