package com.soma.backend.domain.adjuster.entity;

import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 자격 증빙 값 객체 — 자격증 번호와 자격증 파일. 둘 다 각각은 선택이지만 최소 하나는 있어야 한다.
 *
 * <p>이 불변식을 생성 시점에 강제하므로 <b>존재하는 {@code LicenseProof}는 언제나 증빙을 하나 이상
 * 갖는다</b>. 이전에는 같은 규칙이 요청 DTO({@code CreateAdjusterApplicationRequest.hasLicenseProof})에
 * 있어서 서비스가 호출을 잊으면 규칙이 통째로 빠질 수 있었다.
 */
public record LicenseProof(@Nullable String licenseNo, @Nullable String licenseImageUrl) {

  public LicenseProof {
    if (!StringUtils.hasText(licenseNo) && !StringUtils.hasText(licenseImageUrl)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
  }
}
