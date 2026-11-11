package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code user_insurances.insurer_name}(가입 보험사명) 컬럼 암복호화.
 * AAD = {@code user_insurances:insurer_name}.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code UserInsurance} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserInsuranceInsurerNameConverter extends PiiStringConverter {

  public UserInsuranceInsurerNameConverter(PiiCipher cipher) {
    super(cipher, "user_insurances", "insurer_name");
  }
}
