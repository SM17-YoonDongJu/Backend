package com.soma.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * STOMP 핸드셰이크 인증(설계서 §5, WS: "쿠키 없는 핸드셰이크 거부") 단위 테스트.
 * {@code access_token} 쿠키 부재·토큰 검증 실패 시 401로 거부하고, 유효하면 세션 attribute에 userId를 심는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatHandshakeInterceptorTest {

  @Mock
  private CookieProvider cookieProvider;
  @Mock
  private JwtProvider jwtProvider;
  @Mock
  private WebSocketHandler webSocketHandler;

  @InjectMocks
  private ChatHandshakeInterceptor interceptor;

  private ServletServerHttpRequest servletRequest(MockHttpServletRequest mockRequest) {
    return new ServletServerHttpRequest(mockRequest);
  }

  @Test
  @DisplayName("access_token 쿠키가 없으면 핸드셰이크를 거부(401)한다")
  void beforeHandshake_noCookie_rejectsWith401() {
    ServerHttpRequest request = servletRequest(new MockHttpServletRequest());
    ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
    Map<String, Object> attributes = new HashMap<>();
    given(cookieProvider.readCookie(any(), org.mockito.ArgumentMatchers.eq(CookieProvider.ACCESS_TOKEN_COOKIE)))
        .willReturn(Optional.empty());

    boolean accepted = interceptor.beforeHandshake(request, response, webSocketHandler, attributes);

    assertThat(accepted).isFalse();
    assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(attributes).isEmpty();
  }

  @Test
  @DisplayName("토큰이 위조·만료면 핸드셰이크를 거부(401)한다")
  void beforeHandshake_invalidToken_rejectsWith401() {
    ServerHttpRequest request = servletRequest(new MockHttpServletRequest());
    ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
    Map<String, Object> attributes = new HashMap<>();
    given(cookieProvider.readCookie(any(), org.mockito.ArgumentMatchers.eq(CookieProvider.ACCESS_TOKEN_COOKIE)))
        .willReturn(Optional.of("bad-token"));
    org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INVALID_TOKEN))
        .when(jwtProvider).validate("bad-token");

    boolean accepted = interceptor.beforeHandshake(request, response, webSocketHandler, attributes);

    assertThat(accepted).isFalse();
    assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(attributes).isEmpty();
  }

  @Test
  @DisplayName("유효한 토큰이면 핸드셰이크를 허용하고 세션 attribute에 userId를 담는다")
  void beforeHandshake_validToken_acceptsAndStoresUserId() {
    ServerHttpRequest request = servletRequest(new MockHttpServletRequest());
    ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
    Map<String, Object> attributes = new HashMap<>();
    UUID userId = UUID.randomUUID();
    given(cookieProvider.readCookie(any(), org.mockito.ArgumentMatchers.eq(CookieProvider.ACCESS_TOKEN_COOKIE)))
        .willReturn(Optional.of("good-token"));
    given(jwtProvider.getUserId("good-token")).willReturn(userId);

    boolean accepted = interceptor.beforeHandshake(request, response, webSocketHandler, attributes);

    assertThat(accepted).isTrue();
    assertThat(attributes).containsEntry(ChatHandshakeInterceptor.USER_ID_ATTRIBUTE, userId);
  }

  @Test
  @DisplayName("Servlet 기반 요청이 아니면 401로 거부한다")
  void beforeHandshake_nonServletRequest_rejectsWith401() {
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
    Map<String, Object> attributes = new HashMap<>();

    boolean accepted = interceptor.beforeHandshake(request, response, webSocketHandler, attributes);

    assertThat(accepted).isFalse();
    assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }
}
