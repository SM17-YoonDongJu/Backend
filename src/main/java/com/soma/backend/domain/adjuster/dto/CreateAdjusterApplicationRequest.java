package com.soma.backend.domain.adjuster.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 손해사정사 자격 신청 요청(POST /users/adjuster-applications).
 *
 * <p>licenseNo/licenseImageUrl은 각각 선택이지만 최소 하나는 있어야 한다({@link #hasLicenseProof()}).
 * 이 규칙은 명세의 {@code MISSING_REQUIRED_FIELD} code와 맞추기 위해 서비스에서 검증한다.
 *
 * @param affiliation 소속 형태 코드(INDEPENDENT/FIRM). enum 변환·검증은 서비스가 수행한다.
 */
public record CreateAdjusterApplicationRequest(
    @NotBlank(message = "이름은 필수입니다.") @Size(max = 100) String name,
    @NotBlank(message = "연락처는 필수입니다.") @Size(max = 20) String phone,
    @NotEmpty(message = "전문분야는 최소 하나 이상 필요합니다.")
        List<@NotBlank @Size(max = 30) String> specialties,
    @Nullable @Size(max = 100) String licenseNo,
    @Nullable @Size(max = 500) String licenseImageUrl,
    @Nullable Integer career,
    @Nullable String introduction,
    @NotBlank(message = "소속은 필수입니다.") String affiliation,
    @NotBlank(message = "활동 지역은 필수입니다.") @Size(max = 100) String region,
    @NotBlank(message = "등록증 파일은 필수입니다.") @Size(max = 500) String registrationImageUrl) {

  /** 자격증 번호 또는 자격증 파일 중 최소 하나 충족 여부(둘 다 비면 신청 불가). */
  public boolean hasLicenseProof() {
    return hasText(licenseNo) || hasText(licenseImageUrl);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
