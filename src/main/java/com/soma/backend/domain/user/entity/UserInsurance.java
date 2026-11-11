package com.soma.backend.domain.user.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.global.security.crypto.converter.UserInsuranceCoveragesConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceEnrolledAtConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceInsurerNameConverter;
import com.soma.backend.global.security.crypto.converter.UserInsurancePolicyNoConverter;
import com.soma.backend.global.security.crypto.converter.UserInsuranceProductNameConverter;

/**
 * USER_INSURANCES — 회원이 가입한 보험(증권) 항목. 마이페이지 "내 보험 정보" 목록의 읽기 모델이다.
 *
 * <p>현재는 조회(GET /users/me/insurances) 전용으로 매핑한다. {@code created_at}은 DB DEFAULT(now())가
 * 채우므로 insert 대상에서 제외한다. 이 읽기 모델에 필요 없는 컬럼(product_id·ocr_result_id·match_status)은
 * 매핑을 생략했다(스키마에는 존재) — 특히 match_status(약관 마스터 fuzzy 매칭 상태)는 마이페이지 화면(피그마)에
 * 노출되지 않아 응답에서 제외한다.
 *
 * <p>보험사명·상품명·증권번호·가입일·담보 5개 컬럼은 PII라 DB에 AES-256-GCM 봉투(`bytea`)로 저장한다
 * (design.md §7.2·V34). 자바 타입은 그대로 두고 JDBC 경계의 컨버터가 왕복시키므로 도메인 모델은 영향이 없다.
 * {@code coverages}는 JSON 배열로 직렬화해 통째로 암호화하므로 더 이상 `text[]`(JdbcTypeCode.ARRAY)가 아니다.
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

  @Convert(converter = UserInsuranceInsurerNameConverter.class)
  @Column(name = "insurer_name", nullable = false)
  private String insurerName;

  @Convert(converter = UserInsuranceProductNameConverter.class)
  @Column(name = "product_name", nullable = false)
  private String productName;

  @Convert(converter = UserInsurancePolicyNoConverter.class)
  @Column(name = "policy_no")
  private @Nullable String policyNo;

  @Convert(converter = UserInsuranceEnrolledAtConverter.class)
  @Column(name = "enrolled_at")
  private @Nullable LocalDate enrolledAt;

  @Convert(converter = UserInsuranceCoveragesConverter.class)
  @Column(name = "coverages")
  private @Nullable List<String> coverages;

  @Column(name = "policy_file_url")
  private @Nullable String policyFileUrl;

  @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
  private LocalDateTime createdAt;
}
