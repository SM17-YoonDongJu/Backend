package com.soma.backend.domain.report.entity;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보험 상품 기준정보(insurance_products) 읽기 전용 매핑. 검수 화면 맥락 조회에 필요한 컬럼만 매핑하고
 * (category·version은 쓰지 않아 제외), 보험사는 객체가 아니라 ID({@code insurerId})로 참조한다.
 */
@Entity
@Immutable
@Table(name = "insurance_products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsuranceProduct {

  @Id
  private UUID id;

  @Column(name = "insurer_id", nullable = false)
  private UUID insurerId;

  @Column(name = "product_name")
  private String productName;
}
