package com.soma.backend.global.security;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.exception.BusinessException;

/**
 * STOMP 핸드셰이크 인증. {@code access_token} HttpOnly 쿠키의 JWT를 검증해 userId를 세션 attribute에 심는다
 * ({@link ChatHandshakeHandler}가 이를 읽어 Principal로 승격). 실패 시 401로 핸드셰이크를 거부한다
 * (설계서 §5, CHAT_WS_UNAUTHORIZED).
 */
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

  /** 세션 attribute 키 — 인증된 userId(UUID)를 담는다. */
  public static final String USER_ID_ATTRIBUTE = "chatUserId";

  private final CookieProvider cookieProvider;
  private final JwtProvider jwtProvider;

  @Override
  public boolean beforeHandshake(
      @NonNull ServerHttpRequest request,
      @NonNull ServerHttpResponse response,
      @NonNull WebSocketHandler wsHandler,
      @NonNull Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    Optional<String> token =
        cookieProvider.readCookie(servletRequest.getServletRequest(), CookieProvider.ACCESS_TOKEN_COOKIE);
    if (token.isEmpty()) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    try {
      jwtProvider.validate(token.get());
      UUID userId = jwtProvider.getUserId(token.get());
      attributes.put(USER_ID_ATTRIBUTE, userId);
      return true;
    } catch (BusinessException ex) {
      // 토큰 위조·만료 등 검증 실패 → 핸드셰이크 거부(401).
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
  }

  @Override
  public void afterHandshake(
      @NonNull ServerHttpRequest request,
      @NonNull ServerHttpResponse response,
      @NonNull WebSocketHandler wsHandler,
      Exception exception) {
    // 후처리 없음.
  }
}
