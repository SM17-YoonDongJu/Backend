package com.soma.backend.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@DisplayName("UserType 단위 테스트")
class UserTypeTest {

  @Test
  @DisplayName("insured_person은 INSURED_PERSON으로 매핑되고 Role.USER를 반환한다")
  void from_insuredPerson_mapsToUserRole() {
    // When
    UserType type = UserType.from("insured_person");

    // Then
    assertThat(type).isEqualTo(UserType.INSURED_PERSON);
    assertThat(type.toRole()).isEqualTo(Role.USER);
  }

  @Test
  @DisplayName("adjuster는 ADJUSTER로 매핑되고 Role.UNCERTIFICATED_ADJUSTER를 반환한다")
  void from_adjuster_mapsToUncertificatedAdjusterRole() {
    // When
    UserType type = UserType.from("adjuster");

    // Then
    assertThat(type).isEqualTo(UserType.ADJUSTER);
    assertThat(type.toRole()).isEqualTo(Role.UNCERTIFICATED_ADJUSTER);
  }

  @Test
  @DisplayName("대소문자를 구분하지 않고 매핑한다")
  void from_isCaseInsensitive() {
    // When & Then
    assertThat(UserType.from("INSURED_PERSON")).isEqualTo(UserType.INSURED_PERSON);
    assertThat(UserType.from("Adjuster")).isEqualTo(UserType.ADJUSTER);
  }

  @Test
  @DisplayName("알 수 없는 user_type이면 INVALID_REQUEST를 던진다")
  void from_unknownValue_throwsInvalidRequest() {
    // When & Then
    assertThatThrownBy(() -> UserType.from("unknown"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_REQUEST);
  }
}
