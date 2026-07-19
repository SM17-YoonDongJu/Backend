package com.soma.backend.domain.adjuster.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

  @Column(name = "speciality", length = 30)
  private String speciality;

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
   * PENDING으로 함께 생성한다. licenseNo/licenseImageUrl 최소 하나 충족 검증은 호출 서비스가 수행한다.
   */
  public static AdjusterApplication create(
      UUID userId, String name, String speciality, String licenseNo, String licenseImageUrl,
      Integer career, String introduction, Affiliation affiliation, String region,
      String registrationImageUrl) {
    AdjusterApplication application = new AdjusterApplication();
    application.userId = userId;
    application.name = name;
    application.speciality = speciality;
    application.licenseNo = licenseNo;
    application.licenseImageUrl = licenseImageUrl;
    application.career = career;
    application.introduction = introduction;
    application.affiliation = affiliation;
    application.region = region;
    application.registrationImageUrl = registrationImageUrl;
    application.status = ApplicationStatus.PENDING;
    application.documents.add(AdjusterApplicationDocument.pending(DocumentType.LICENSE));
    application.documents.add(AdjusterApplicationDocument.pending(DocumentType.REGISTRATION));
    return application;
  }
}
