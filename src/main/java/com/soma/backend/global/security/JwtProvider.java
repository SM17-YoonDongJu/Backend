package com.soma.backend.global.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
* JWT 토큰 생성·검증·파싱을 담당한다.
*/
@Component
public class JwtProvider {

  private static final String ROLE_CLAIM = "role";

  private final SecretKey secretKey;
  private final long accessTokenExpiry;
  private final long refreshTokenExpiry;

  public JwtProvider(
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.access-token-expiry}") long accessTokenExpiry,
      @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
    this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenExpiry = accessTokenExpiry;
    this.refreshTokenExpiry = refreshTokenExpiry;
  }

  /**
  * Access Token 발급. subject=userId, role 클레임 포함.
  */
  public String generateAccessToken(UUID userId, String role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + accessTokenExpiry);
    return Jwts.builder()
        .subject(userId.toString())
        .claim(ROLE_CLAIM, role)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(secretKey, Jwts.SIG.HS256)
        .compact();
  }

  /**
  * Refresh Token 발급. subject=userId만 포함.
  */
  public String generateRefreshToken(UUID userId) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + refreshTokenExpiry);
    return Jwts.builder()
        .subject(userId.toString())
        .issuedAt(now)
        .expiration(expiry)
        .signWith(secretKey, Jwts.SIG.HS256)
        .compact();
  }

  /**
  * 서명·만료를 검증한다. 만료 시 EXPIRED_TOKEN, 위조·형식 오류 시 INVALID_TOKEN을 던진다.
  */
  public void validate(String token) {
    try {
      parseClaims(token);
    } catch (ExpiredJwtException e) {
      throw new BusinessException(ErrorCode.EXPIRED_TOKEN);
    } catch (JwtException | IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }
  }

  /**
  * 토큰 subject에서 userId(UUID)를 추출한다.
  */
  public UUID getUserId(String token) {
    return UUID.fromString(parseClaims(token).getSubject());
  }

  /**
  * 토큰 role 클레임을 추출한다.
  */
  public String getRole(String token) {
    return parseClaims(token).get(ROLE_CLAIM, String.class);
  }

  private Claims parseClaims(String token) {
    return Jwts.parser()
        .verifyWith(secretKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }
}
