package com.soma.backend.global.security;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.redis.RefreshTokenRepository;
import com.soma.backend.infra.redis.RefreshTokenRepository.RotateResult;

/**
 * 토큰 발급·RTR·쿠키 부착의 저수준 재사용 진입점.
 *
 * <p>도메인 인증 서비스(로그인·재발급·로그아웃)가 이 서비스를 오케스트레이션한다. role 조회 등
 * 도메인 의존 로직은 이 서비스가 하지 않고, 도메인 서비스가 role을 넘겨준다.
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

  private final JwtProvider jwtProvider;
  private final CookieProvider cookieProvider;
  private final RefreshTokenRepository refreshTokenRepository;

  /**
   * Access·Refresh 토큰을 발급해 Redis에 RT를 저장하고 두 쿠키를 응답에 부착한다.
   * 토큰은 응답 바디에 노출하지 않는다.
   */
  public void issueTokens(HttpServletResponse response, UUID userId, String role) {
    String accessToken = jwtProvider.generateAccessToken(userId, role);
    String refreshToken = jwtProvider.generateRefreshToken(userId);
    refreshTokenRepository.save(userId, refreshToken);
    addCookie(response, cookieProvider.buildAccessTokenCookie(accessToken));
    addCookie(response, cookieProvider.buildRefreshTokenCookie(refreshToken));
  }

  /**
   * refresh_token 쿠키의 서명·만료를 검증하고 원자적 회전에 필요한 컨텍스트를 반환한다(RTR 재발급 1단계).
   * Redis 저장값 대조·회전은 {@link #reissueTokens}에서 원자적으로 수행한다(경쟁 창 제거).
   *
   * <ul>
   *   <li>쿠키 없음 → {@code LOGIN_REQUIRED}(401)</li>
   *   <li>서명·만료 오류 → JwtProvider의 예외(INVALID_TOKEN/EXPIRED_TOKEN) 전파</li>
   * </ul>
   */
  public RefreshTokenContext validateRefreshCookie(HttpServletRequest request) {
    String refreshToken = cookieProvider.readCookie(request, CookieProvider.REFRESH_TOKEN_COOKIE)
        .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));

    jwtProvider.validate(refreshToken);
    UUID userId = jwtProvider.getUserId(refreshToken);
    return new RefreshTokenContext(userId, refreshToken);
  }

  /**
   * RTR 재발급 2단계: 새 토큰 쌍을 만들고 Redis 저장값을 원자적으로 교체(CAS)한 뒤 쿠키를 부착한다.
   * 저장값이 1단계에서 검증한 refresh 토큰과 일치할 때만 성공하므로 동시 재발급 시 한 요청만 통과한다.
   *
   * <ul>
   *   <li>저장값 없음(만료·미존재) → {@code REFRESH_TOKEN_NOT_FOUND}(401)</li>
   *   <li>저장값 불일치(이미 회전됨·탈취 의심) → Redis delete 후 {@code INVALID_TOKEN}(401)</li>
   * </ul>
   */
  public void reissueTokens(
      HttpServletResponse response, RefreshTokenContext context, String role) {
    String accessToken = jwtProvider.generateAccessToken(context.userId(), role);
    String refreshToken = jwtProvider.generateRefreshToken(context.userId());
    RotateResult result =
        refreshTokenRepository.rotate(context.userId(), context.refreshToken(), refreshToken);
    switch (result) {
      case ROTATED -> {
        addCookie(response, cookieProvider.buildAccessTokenCookie(accessToken));
        addCookie(response, cookieProvider.buildRefreshTokenCookie(refreshToken));
      }
      case NOT_FOUND -> throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
      case MISMATCH -> throw new BusinessException(ErrorCode.INVALID_TOKEN);
      default -> throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }
  }

  /**
   * Redis RT를 삭제하고 만료 쿠키를 부착한다(로그아웃). userId가 null이면 쿠키만 만료시킨다(멱등).
   */
  public void clearTokens(HttpServletResponse response, UUID userId) {
    if (userId != null) {
      refreshTokenRepository.delete(userId);
    }
    addCookie(response, cookieProvider.expireAccessTokenCookie());
    addCookie(response, cookieProvider.expireRefreshTokenCookie());
  }

  private void addCookie(HttpServletResponse response, ResponseCookie cookie) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  /**
   * refresh 쿠키 검증 결과. 원자적 회전(rotate)에 필요한 userId와 원본 refresh 토큰을 함께 전달한다.
   */
  public record RefreshTokenContext(UUID userId, String refreshToken) {
  }
}
