package com.soma.backend.domain.adjuster.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 손해사정사 자격 신청 요청(POST /users/adjuster-applications).
 *
 * <p>licenseNo/licenseImageUrl은 각각 선택이지만 최소 하나는 있어야 한다. 필드 하나로는 표현할 수 없는
 * 규칙이라 Bean Validation이 아니라 도메인이 갖는다 — {@code LicenseProof} 생성 시점에 강제되며
 * 위반하면 {@code MISSING_REQUIRED_FIELD}(400)다.
 *
 * @param affiliation 소속 형태 코드(INDEPENDENT/FIRM). enum 변환·검증은 {@code Affiliation.from}이 맡는다.
 */
public record CreateAdjusterApplicationRequest(
    @NotBlank(message = "이름은 필수입니다.") @Size(max = 100) String name,
    @NotBlank(message = "연락처는 필수입니다.") @Size(max = 20) String phone,
    @NotEmpty(message = "전문분야는 최소 하나 이상 필요합니다.")
        List<@NotBlank @Size(max = 30) String> specialties,
    @Nullable @Schema(nullable = true) @Size(max = 100) String licenseNo,
    @Nullable @Schema(nullable = true) @Size(max = 500) String licenseImageUrl,
    @Nullable @Schema(nullable = true) Integer career,
    @Nullable @Schema(nullable = true) String introduction,
    @NotBlank(message = "소속은 필수입니다.") String affiliation,
    @NotBlank(message = "활동 지역은 필수입니다.") @Size(max = 100) String region,
    @NotBlank(message = "등록증 파일은 필수입니다.") @Size(max = 500) String registrationImageUrl) {
}
