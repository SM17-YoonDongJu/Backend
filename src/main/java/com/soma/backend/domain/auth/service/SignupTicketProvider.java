package com.soma.backend.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 신규 소셜 사용자에게 발급하는 단기 가입 티켓(short-lived signed JWT)의 발급·검증.
 *
 * <p>{@code jwt.secret}으로 서명하되 {@code purpose="signup"} 클레임으로 일반 액세스 토큰과 구분한다.
 * TTL 5분. 위조·만료·purpose 불일치 시 {@link ErrorCode#INVALID_TOKEN}.
 */
@Component
public class SignupTicketProvider {

  private static final String PURPOSE_CLAIM = "purpose";
  private static final String PURPOSE_SIGNUP = "signup";
  private static final String PROVIDER_CLAIM = "provider";
  private static final long TICKET_EXPIRY_MILLIS = 5 * 60 * 1000L;

  private final SecretKey secretKey;

  public SignupTicketProvider(@Value("${jwt.secret}") String secret) {
    this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 가입 티켓을 발급한다. subject=providerUserId, provider 클레임 포함.
   */
  public String issue(String provider, String providerUserId) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + TICKET_EXPIRY_MILLIS);
    return Jwts.builder()
        .subject(providerUserId)
        .claim(PURPOSE_CLAIM, PURPOSE_SIGNUP)
        .claim(PROVIDER_CLAIM, provider)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(secretKey, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * 가입 티켓을 검증·파싱한다. 서명·만료·purpose 불일치 시 INVALID_TOKEN.
   */
  public SignupTicket parse(String token) {
    try {
      Claims claims = Jwts.parser()
          .verifyWith(secretKey)
          .build()
          .parseSignedClaims(token)
          .getPayload();
      if (!PURPOSE_SIGNUP.equals(claims.get(PURPOSE_CLAIM, String.class))) {
        throw new BusinessException(ErrorCode.INVALID_TOKEN);
      }
      return new SignupTicket(
          claims.get(PROVIDER_CLAIM, String.class),
          claims.getSubject());
    } catch (JwtException | IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }
  }
}
