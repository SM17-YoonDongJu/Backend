package com.soma.backend.domain.user.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

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
public record UserInsuranceListResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> list) {

  /** 보험 항목 1건. */
  public record Item(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String insurerName,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productName,
      @Nullable @Schema(nullable = true) String policyNo,
      @Nullable @Schema(nullable = true) LocalDate enrolledAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> coverages,
      @Nullable @Schema(nullable = true) String policyFileUrl) {

    public static Item from(UserInsurance insurance, UnaryOperator<String> urlResolver) {
      return new Item(
          insurance.getId(),
          insurance.getInsurerName(),
          insurance.getProductName(),
          insurance.getPolicyNo(),
          insurance.getEnrolledAt(),
          insurance.getCoverages() == null ? List.of() : insurance.getCoverages(),
          urlResolver.apply(insurance.getPolicyFileUrl()));
    }
  }

  /**
   * @param urlResolver 저장된 {@code policy_file_url}(private S3 객체)을 단기 presigned GET URL로 치환하는
   *                    함수(보통 {@code S3UploadService::presignedGetUrl}). 미등록(null)·외부 URL은 그대로 통과한다.
   */
  public static UserInsuranceListResponse from(
      List<UserInsurance> insurances, UnaryOperator<String> urlResolver) {
    return new UserInsuranceListResponse(
        insurances.stream().map(insurance -> Item.from(insurance, urlResolver)).toList());
  }
}
