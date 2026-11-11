package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code user_insurances.product_name}(가입 보험상품명) 컬럼 암복호화.
 * AAD = {@code user_insurances:product_name}.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code UserInsurance} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserInsuranceProductNameConverter extends PiiStringConverter {

  public UserInsuranceProductNameConverter(PiiCipher cipher) {
    super(cipher, "user_insurances", "product_name");
  }
}
