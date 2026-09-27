package com.soma.backend.domain.adjuster.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * LicenseProof 불변식 단위 테스트 — "자격증 번호와 파일 중 최소 하나"를 생성 시점에 강제하는지 검증한다.
 * 이 규칙은 이전에 요청 DTO에 있었고 서비스가 호출해야만 적용됐다.
 */
@DisplayName("LicenseProof 자격 증빙 불변식")
class LicenseProofTest {

  private void assertMissingRequiredField(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("자격증 번호만 있어도 생성된다")
  void licenseNoOnly() {
    assertThatCode(() -> new LicenseProof("제2024-0001호", null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("자격증 파일만 있어도 생성된다")
  void licenseImageOnly() {
    assertThatCode(() -> new LicenseProof(null, "https://x/license.pdf")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("둘 다 없으면 MISSING_REQUIRED_FIELD")
  void bothNull() {
    assertMissingRequiredField(() -> new LicenseProof(null, null));
  }

  @Test
  @DisplayName("둘 다 공백 문자열이면 MISSING_REQUIRED_FIELD — null만 막으면 빈 값이 통과한다")
  void bothBlank() {
    assertMissingRequiredField(() -> new LicenseProof("  ", ""));
  }
}
