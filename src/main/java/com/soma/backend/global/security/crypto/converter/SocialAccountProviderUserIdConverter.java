package com.soma.backend.global.security.crypto.converter;

import org.springframework.stereotype.Component;

import jakarta.persistence.Converter;

import com.soma.backend.global.security.crypto.PiiCipher;

/**
 * {@code social_accounts.provider_user_id} 컬럼 암복호화. AAD = {@code social_accounts:provider_user_id}.
 *
 * <p>조회(OAuth 로그인 시 (provider, providerUserId) 식별)는 이 컬럼이 아니라 별도 HMAC 블라인드 인덱스
 * 컬럼({@code provider_user_id_hmac})으로 한다 — GCM 암호문은 매번 nonce가 달라 동등비교가 불가능하다.
 *
 * <p>{@code autoApply = false} — 적용 대상은 {@code SocialAccount} 엔티티의 {@code @Convert} 선언뿐이다.
 */
@Component
@Converter(autoApply = false)
public class SocialAccountProviderUserIdConverter extends PiiStringConverter {

  public SocialAccountProviderUserIdConverter(PiiCipher cipher) {
    super(cipher, "social_accounts", "provider_user_id");
  }
}
