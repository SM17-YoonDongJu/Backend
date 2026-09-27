package com.soma.backend.domain.adjuster.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * ADJUSTER_APPLICATIONS Aggregate Root — 손해사정사 자격 신청서. User와는 userId(UUID)로만 연결한다.
 * 신청 접수 시 증빙 문서(LICENSE/REGISTRATION)를 PENDING으로 함께 생성하며, 문서 컬렉션의
 * 생명주기는 이 Root가 소유한다(외부 Repository 직접 접근 금지). 승인·반려 전이는 관리자 승인 API 소관이다.
 */
@Entity
@Table(name = "adjuster_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterApplication extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Column(name = "phone", length = 20)
  private String phone;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "specialties", columnDefinition = "text[]")
  private List<String> specialties;

  @Column(name = "license_no", length = 100)
  private String licenseNo;

  @Column(name = "license_image_url", length = 500)
  private String licenseImageUrl;

  @Column(name = "career")
  private Integer career;

  @Column(name = "introduction")
  private String introduction;

  @Enumerated(EnumType.STRING)
  @Column(name = "affiliation", length = 20)
  private Affiliation affiliation;

  @Column(name = "region", length = 100)
  private String region;

  @Column(name = "registration_image_url", length = 500)
  private String registrationImageUrl;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ApplicationStatus status;

  @Column(name = "reject_reason")
  private String rejectReason;

  @Column(name = "rejected_at")
  private LocalDateTime rejectedAt;

  /** 증빙 문서 심사 상태 — 이 Aggregate가 소유·생명주기 관리(외부 Repository로 직접 저장/삭제하지 않음). */
  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "application_id", nullable = false)
  private List<AdjusterApplicationDocument> documents = new ArrayList<>();

  /**
   * 자격 신청서 생성(정적 팩터리). 상태는 PENDING으로 시작하고, 증빙 문서 2종(자격증·등록증)을
   * PENDING으로 함께 생성한다.
   *
   * <p>인자는 의미 단위로 묶어 받는다 — 인적사항({@link ApplicantProfile}), 자격 증빙
   * ({@link LicenseProof}), 활동 범위({@link ActivityScope}). 자격 증빙의 "번호·파일 중 최소 하나"
   * 불변식은 {@code LicenseProof}가 생성 시점에 이미 보장하므로 여기서 다시 검사하지 않는다.
   * 저장 필드는 평평하게 유지해 DB 매핑은 그대로 둔다(묶음은 생성 계약에만 쓰인다).
   */
  public static AdjusterApplication create(
      UUID userId, ApplicantProfile profile, LicenseProof licenseProof, ActivityScope scope,
      String registrationImageUrl) {
    AdjusterApplication application = new AdjusterApplication();
    application.userId = userId;
    application.name = profile.name();
    application.phone = profile.phone();
    application.career = profile.career();
    application.introduction = profile.introduction();
    application.licenseNo = licenseProof.licenseNo();
    application.licenseImageUrl = licenseProof.licenseImageUrl();
    application.specialties = scope.specialties();
    application.region = scope.region();
    application.affiliation = scope.affiliation();
    application.registrationImageUrl = registrationImageUrl;
    application.status = ApplicationStatus.PENDING;
    application.documents.add(AdjusterApplicationDocument.pending(DocumentType.LICENSE));
    application.documents.add(AdjusterApplicationDocument.pending(DocumentType.REGISTRATION));
    return application;
  }
}
