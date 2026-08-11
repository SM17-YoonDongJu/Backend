package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code user_insurances.coverages}(가입 담보 목록, `text[]` → `bytea`) 컬럼 암복호화 —
 * JSON 배열로 직렬화한 뒤 통째로 1회 암호화한다(design.md §6).
 * AAD = {@code user_insurances:coverages}.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code UserInsurance} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserInsuranceCoveragesConverter extends PiiStringListConverter {

  public UserInsuranceCoveragesConverter(PiiCipher cipher, JsonMapper jsonMapper) {
    super(cipher, jsonMapper, "user_insurances", "coverages");
  }
}
