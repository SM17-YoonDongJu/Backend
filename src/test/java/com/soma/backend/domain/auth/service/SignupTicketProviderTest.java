package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@DisplayName("SignupTicketProvider 단위 테스트")
class SignupTicketProviderTest {

  private static final String SECRET = "signup-ticket-secret-key-at-least-32-bytes!!";
  private static final String OTHER_SECRET = "another-totally-different-secret-key-32bytes!!";

  private final SignupTicketProvider provider = new SignupTicketProvider(SECRET);

  @Test
  @DisplayName("issue로 발급한 티켓을 parse하면 provider·providerUserId가 복원된다")
  void issueThenParse_restoresClaims() {
    // Given
    String token = provider.issue("kakao", "kakao-123");

    // When
    SignupTicket ticket = provider.parse(token);

    // Then
    assertThat(ticket.provider()).isEqualTo("kakao");
    assertThat(ticket.providerUserId()).isEqualTo("kakao-123");
  }

  @Test
  @DisplayName("만료된 티켓을 parse하면 INVALID_TOKEN을 던진다")
  void parse_expiredTicket_throwsInvalidToken() {
    // Given
    String expired = buildToken(SECRET, "signup", new Date(System.currentTimeMillis() - 1000L));

    // When & Then
    assertThatThrownBy(() -> provider.parse(expired))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_TOKEN);
  }

  @Test
  @DisplayName("다른 키로 서명된(위조) 티켓을 parse하면 INVALID_TOKEN을 던진다")
  void parse_forgedTicket_throwsInvalidToken() {
    // Given
    String forged = buildToken(OTHER_SECRET, "signup", new Date(System.currentTimeMillis() + 60000L));

    // When & Then
    assertThatThrownBy(() -> provider.parse(forged))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_TOKEN);
  }

  @Test
  @DisplayName("purpose가 signup이 아닌 티켓을 parse하면 INVALID_TOKEN을 던진다")
  void parse_wrongPurpose_throwsInvalidToken() {
    // Given
    String wrongPurpose = buildToken(SECRET, "access", new Date(System.currentTimeMillis() + 60000L));

    // When & Then
    assertThatThrownBy(() -> provider.parse(wrongPurpose))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_TOKEN);
  }

  private String buildToken(String secret, String purpose, Date expiry) {
    SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    return Jwts.builder()
        .subject("kakao-123")
        .claim("purpose", purpose)
        .claim("provider", "kakao")
        .issuedAt(new Date(System.currentTimeMillis() - 5000L))
        .expiration(expiry)
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }
}
