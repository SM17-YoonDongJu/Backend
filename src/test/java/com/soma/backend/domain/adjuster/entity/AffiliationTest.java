package com.soma.backend.domain.adjuster.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * Affiliation.from 변환 계약 테스트. 서비스의 private parseAffiliation을 enum으로 옮기면서
 * ChatReportReason.from과 같은 계약(누락=MISSING_REQUIRED_FIELD, 미지원=VALIDATION_ERROR)을 갖췄다.
 */
@DisplayName("Affiliation 소속 코드 변환")
class AffiliationTest {

  @Test
  @DisplayName("정의된 코드는 enum으로 변환된다")
  void fromValidCode() {
    assertThat(Affiliation.from("INDEPENDENT")).isEqualTo(Affiliation.INDEPENDENT);
    assertThat(Affiliation.from("FIRM")).isEqualTo(Affiliation.FIRM);
  }

  @Test
  @DisplayName("null이면 MISSING_REQUIRED_FIELD — 이전 구현은 여기서 NPE가 났다")
  void fromNull() {
    assertThatThrownBy(() -> Affiliation.from(null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("정의되지 않은 값이면 VALIDATION_ERROR")
  void fromUnknownCode() {
    assertThatThrownBy(() -> Affiliation.from("AGENCY"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }
}
