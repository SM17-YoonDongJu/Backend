package com.soma.backend.domain.auth.entity;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 회원가입 요청의 사용자 유형. API 계약(snake_case 문자열)을 도메인 {@link Role}로 매핑한다.
 */
public enum UserType {
  INSURED_PERSON("insured_person", Role.USER),
  ADJUSTER("adjuster", Role.UNCERTIFICATED_ADJUSTER);

  private final String apiValue;
  private final Role role;

  UserType(String apiValue, Role role) {
    this.apiValue = apiValue;
    this.role = role;
  }

  /**
   * API 문자열(insured_person|adjuster)을 UserType으로 변환한다. 알 수 없는 값이면 400.
   */
  public static UserType from(String apiValue) {
    for (UserType type : values()) {
      if (type.apiValue.equalsIgnoreCase(apiValue)) {
        return type;
      }
    }
    throw new BusinessException(ErrorCode.INVALID_REQUEST);
  }

  public Role toRole() {
    return role;
  }
}
