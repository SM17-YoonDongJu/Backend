package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code users.phone_number} 컬럼 암복호화. AAD = {@code users:phone_number}.
 *
 * <p>조회(가입 중복확인·프로필 수정)는 이 컬럼이 아니라 별도 HMAC 블라인드 인덱스 컬럼
 * ({@code phone_number_hmac})으로 한다 — GCM 암호문은 매번 nonce가 달라 동등비교가 불가능하다.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code User} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class UserPhoneNumberConverter extends PiiStringConverter {

  public UserPhoneNumberConverter(PiiCipher cipher) {
    super(cipher, "users", "phone_number");
  }
}
