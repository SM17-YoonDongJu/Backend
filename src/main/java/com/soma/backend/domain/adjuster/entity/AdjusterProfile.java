package com.soma.backend.domain.adjuster.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ADJUSTER_PROFILES — 손해사정사 프로필 read 모델(공개 검색·본인 프로필 조회용).
 *
 * <p>User와는 userId(UUID)로만 연결한다. 이 저장소에서 처음 도입되는 adjuster_profiles JPA 매핑이며,
 * 필요한 스칼라·배열 컬럼만 매핑한다(careers jsonb는 현재 read 대상 아님 → 미매핑, validate는
 * 매핑된 컬럼 존재만 검사하므로 무방). 활동지역(activity_region) 구조화는 후속 마이그레이션에서 다룬다.
 */
@Entity
@Table(name = "adjuster_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterProfile {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "license_no")
  private String licenseNo;

  @Column(name = "name")
  private String name;

  @Column(name = "headline")
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

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "consult_methods", columnDefinition = "text[]")
  private List<String> consultMethods;

  @Column(name = "activity_region")
  private String activityRegion;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  @Column(name = "introduction")
  private String introduction;

  @Column(name = "rating_mean")
  private BigDecimal ratingMean;

  @Column(name = "review_count")
  private Integer reviewCount;

  @Column(name = "created_at")
  private LocalDateTime createdAt;
}
