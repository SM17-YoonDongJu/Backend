package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code user_insurances.enrolled_at}(보험 가입일) 컬럼 암복호화 — ISO_LOCAL_DATE 문자열로 직렬화 후 암호화.
 * AAD = {@code user_insurances:enrolled_at}.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code UserInsurance} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserInsuranceEnrolledAtConverter extends PiiLocalDateConverter {

  public UserInsuranceEnrolledAtConverter(PiiCipher cipher) {
    super(cipher, "user_insurances", "enrolled_at");
  }
}
