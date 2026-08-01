package com.soma.backend.domain.user.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.soma.backend.domain.user.entity.UserInsurance;

/**
 * 내 보험 정보 목록 응답(GET /users/me/insurances). 마이페이지 "내 보험 정보" 카드용. 필드는 Jackson 전역
 * 설정으로 snake_case 직렬화된다(insurer_name·product_name·policy_no·enrolled_at·policy_file_url).
 * 페이지네이션 없이 전건을 data.list로 내린다.
 *
 * <p>{@code coverages}는 null이면 빈 배열로 coalesce해 FE 렌더링을 단순화하고(소비처가 .length 무방어 접근),
 * 나머지 미입력 값은 원본 그대로(JSON null, 키는 유지) 내린다. {@code policy_file_url}은 증권 등록 여부
 * 배지("증권 등록됨/미등록")용으로 FE가 요청한 필드이며 등록 전이면 null이다.
 */
public record UserInsuranceListResponse(List<Item> list) {

  /** 보험 항목 1건. */
  public record Item(
      UUID id,
      String insurerName,
      String productName,
      @Nullable String policyNo,
      @Nullable LocalDate enrolledAt,
      List<String> coverages,
      @Nullable String policyFileUrl) {

    public static Item from(UserInsurance insurance) {
      return new Item(
          insurance.getId(),
          insurance.getInsurerName(),
          insurance.getProductName(),
          insurance.getPolicyNo(),
          insurance.getEnrolledAt(),
          insurance.getCoverages() == null ? List.of() : insurance.getCoverages(),
          insurance.getPolicyFileUrl());
    }
  }

  public static UserInsuranceListResponse from(List<UserInsurance> insurances) {
    return new UserInsuranceListResponse(insurances.stream().map(Item::from).toList());
  }
}
