package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code user_insurances.policy_no}(증권번호) 컬럼 암복호화.
 * AAD = {@code user_insurances:policy_no}.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code UserInsurance} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserInsurancePolicyNoConverter extends PiiStringConverter {

  public UserInsurancePolicyNoConverter(PiiCipher cipher) {
    super(cipher, "user_insurances", "policy_no");
  }
}
