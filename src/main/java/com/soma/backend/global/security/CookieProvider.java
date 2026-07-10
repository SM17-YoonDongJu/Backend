package com.soma.backend.global.security;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 인증 토큰을 담는 HttpOnly 쿠키를 생성·만료·조회하는 유틸.
 *
 * <p>{@code app.cookie.secure}(기본 true)와 {@code app.cookie.same-site}(기본 "Lax")로
 * 로컬 http 개발과 운영 https 환경을 프로퍼티로 분리한다.
 */
@Component
public class CookieProvider {

  public static final String ACCESS_TOKEN_COOKIE = "access_token";
  public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

  private static final String ROOT_PATH = "/";
  private static final String AUTH_PATH = "/auth";

  private final Duration accessTokenMaxAge;
  private final Duration refreshTokenMaxAge;
  private final boolean secure;
  private final String sameSite;

  public CookieProvider(
      @Value("${jwt.access-token-expiry}") long accessTokenExpiryMillis,
      @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiryMillis,
      @Value("${app.cookie.secure:true}") boolean secure,
      @Value("${app.cookie.same-site:Lax}") String sameSite) {
    this.accessTokenMaxAge = Duration.ofMillis(accessTokenExpiryMillis);
    this.refreshTokenMaxAge = Duration.ofMillis(refreshTokenExpiryMillis);
    this.secure = secure;
    this.sameSite = sameSite;
  }

  /**
   * Access Token 쿠키를 생성한다. Path는 모든 보호 API에서 검증돼야 하므로 루트로 둔다.
   */
  public ResponseCookie buildAccessTokenCookie(String token) {
    return baseCookie(ACCESS_TOKEN_COOKIE, token, accessTokenMaxAge, ROOT_PATH);
  }

  /**
   * Refresh Token 쿠키를 생성한다. Path를 {@code /auth}로 좁혀 reissue/logout 요청에만 전송되게 한다
   * (일반 API 요청에 장기 크리덴셜이 딸려가지 않도록 노출 표면 축소).
   */
  public ResponseCookie buildRefreshTokenCookie(String token) {
    return baseCookie(REFRESH_TOKEN_COOKIE, token, refreshTokenMaxAge, AUTH_PATH);
  }

  /**
   * Access Token 만료 쿠키(로그아웃용, Max-Age=0)를 생성한다. 삭제되려면 발급 시 Path와 일치해야 한다.
   */
  public ResponseCookie expireAccessTokenCookie() {
    return baseCookie(ACCESS_TOKEN_COOKIE, "", Duration.ZERO, ROOT_PATH);
  }

  /**
   * Refresh Token 만료 쿠키(로그아웃용, Max-Age=0)를 생성한다. 발급 시 Path({@code /auth})와 일치해야
   * 브라우저가 매칭해 삭제한다.
   */
  public ResponseCookie expireRefreshTokenCookie() {
    return baseCookie(REFRESH_TOKEN_COOKIE, "", Duration.ZERO, AUTH_PATH);
  }

  /**
   * 요청 쿠키에서 이름으로 값을 조회한다. 없으면 {@link Optional#empty()}.
   */
  public Optional<String> readCookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    for (Cookie cookie : cookies) {
      if (name.equals(cookie.getName())) {
        return Optional.ofNullable(cookie.getValue());
      }
    }
    return Optional.empty();
  }

  private ResponseCookie baseCookie(String name, String value, Duration maxAge, String path) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite(sameSite)
        .path(path)
        .maxAge(maxAge)
        .build();
  }
}
