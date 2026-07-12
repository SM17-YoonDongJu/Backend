package com.soma.backend.domain.auth.service;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtProvider;

/**
 * 로그아웃 유스케이스. Redis RT 삭제 + 만료 쿠키 부착을 멱등하게 수행한다.
 *
 * <p>{@code /auth/**}가 permitAll이라 인증 principal이 없을 수 있다. principal이 있으면 그 userId로,
 * 없으면 refresh 쿠키에서 best-effort로 userId를 복원해 Redis RT를 지운다. 어떤 경우든 200을 반환한다.
 */
@Service
@RequiredArgsConstructor
public class AuthLogoutService {

  private final AuthTokenService authTokenService;
  private final CookieProvider cookieProvider;
  private final JwtProvider jwtProvider;

  public void logout(
      HttpServletRequest request, HttpServletResponse response, @Nullable UUID principalUserId) {
    UUID userId = principalUserId != null ? principalUserId : resolveFromRefreshCookie(request);
    authTokenService.clearTokens(response, userId);
  }

  private @Nullable UUID resolveFromRefreshCookie(HttpServletRequest request) {
    return cookieProvider.readCookie(request, CookieProvider.REFRESH_TOKEN_COOKIE)
        .map(this::extractUserIdQuietly)
        .orElse(null);
  }

  private @Nullable UUID extractUserIdQuietly(String refreshToken) {
    try {
      return jwtProvider.getUserId(refreshToken);
    } catch (RuntimeException ex) {
      // 만료·위조 쿠키여도 로그아웃은 멱등하게 성공시킨다(쿠키만 만료).
      return null;
    }
  }
}
