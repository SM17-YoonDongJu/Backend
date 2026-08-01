package com.soma.backend.domain.auth.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthLogoutService 단위 테스트")
class AuthLogoutServiceTest {

  @InjectMocks
  private AuthLogoutService authLogoutService;

  @Mock
  private AuthTokenService authTokenService;

  @Mock
  private CookieProvider cookieProvider;

  @Mock
  private JwtProvider jwtProvider;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpServletResponse response;

  @Test
  @DisplayName("principal이 있으면 해당 userId로 토큰을 정리한다")
  void logout_withPrincipal_clearsByPrincipalId() {
    // Given
    UUID userId = UUID.randomUUID();

    // When
    authLogoutService.logout(request, response, userId);

    // Then
    then(authTokenService).should().clearTokens(response, userId);
  }

  @Test
  @DisplayName("principal이 없으면 refresh 쿠키에서 userId를 복원해 정리한다")
  void logout_withoutPrincipal_resolvesFromCookie() {
    // Given
    UUID userId = UUID.randomUUID();
    given(cookieProvider.readCookie(request, CookieProvider.REFRESH_TOKEN_COOKIE))
        .willReturn(Optional.of("refresh-token"));
    given(jwtProvider.getUserId("refresh-token")).willReturn(userId);

    // When
    authLogoutService.logout(request, response, null);

    // Then
    then(authTokenService).should().clearTokens(response, userId);
  }

  @Test
  @DisplayName("principal도 없고 쿠키도 없으면 null로 멱등하게 쿠키만 만료시킨다")
  void logout_noPrincipalNoCookie_clearsWithNull() {
    // Given
    given(cookieProvider.readCookie(request, CookieProvider.REFRESH_TOKEN_COOKIE))
        .willReturn(Optional.empty());

    // When
    authLogoutService.logout(request, response, null);

    // Then
    then(authTokenService).should().clearTokens(eq(response), eq(null));
  }
}
