package com.soma.backend.domain.auth.service.provider.apple;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * Apple {@code id_token}(RS256, Apple JWKS 서명)을 검증하고 {@code sub}를 반환한다.
 *
 * <p>검증 로직은 주입받은 Apple 전용 {@link JwtDecoder}(서명·iss·exp·aud 검증)에 위임한다.
 * decoder 주입 구조라 단위 테스트에서 로컬 키 기반 decoder를 꽂아 검증할 수 있다.
 */
@Component
public class AppleIdTokenVerifier {

  private final JwtDecoder appleJwtDecoder;

  public AppleIdTokenVerifier(@Qualifier("appleJwtDecoder") JwtDecoder appleJwtDecoder) {
    this.appleJwtDecoder = appleJwtDecoder;
  }

  /**
   * id_token을 검증하고 subject(providerUserId)를 반환한다. 검증 실패는 EXTERNAL_API_ERROR로 매핑한다.
   */
  public String verifyAndGetSubject(String idToken) {
    try {
      Jwt jwt = appleJwtDecoder.decode(idToken);
      return jwt.getSubject();
    } catch (JwtException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }
}
