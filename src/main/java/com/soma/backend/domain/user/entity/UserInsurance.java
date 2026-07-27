package com.soma.backend.domain.user.entity;

import java.time.LocalDate;
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

/**
 * USER_INSURANCES — 회원이 가입한 보험(증권) 항목. 마이페이지 "내 보험 정보" 목록의 읽기 모델이다.
 *
 * <p>현재는 조회(GET /users/me/insurances) 전용으로 매핑한다. {@code created_at}은 DB DEFAULT(now())가
 * 채우므로 insert 대상에서 제외한다. 이 읽기 모델에 필요 없는 컬럼(product_id·ocr_result_id·match_status)은
 * 매핑을 생략했다(스키마에는 존재) — 특히 match_status(약관 마스터 fuzzy 매칭 상태)는 마이페이지 화면(피그마)에
 * 노출되지 않아 응답에서 제외한다.
 */
@Entity
@Table(name = "user_insurances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInsurance {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "insurer_name", nullable = false)
  private String insurerName;

  @Column(name = "product_name", nullable = false)
  private String productName;

  @Column(name = "policy_no")
  private @Nullable String policyNo;

  @Column(name = "enrolled_at")
  private @Nullable LocalDate enrolledAt;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "coverages", columnDefinition = "text[]")
  private @Nullable List<String> coverages;

  @Column(name = "policy_file_url")
  private @Nullable String policyFileUrl;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private LocalDateTime createdAt;
}
