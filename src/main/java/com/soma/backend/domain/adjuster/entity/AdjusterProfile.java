package com.soma.backend.domain.adjuster.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;

/**
 * ADJUSTER_PROFILES 최소 골격 엔티티(A1). report 컨텍스트가 조회 전용으로 참조한다.
 */
@Entity
@Table(name = "adjuster_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdjusterProfile extends BaseEntity {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "license_no", length = 100)
  private String licenseNo;

  @Column(name = "speciality", length = 30)
  private String speciality;

  @Column(name = "subscription_plan", length = 30)
  private String subscriptionPlan;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "name", length = 100)
  private String name;
}
