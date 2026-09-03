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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

import com.soma.backend.global.exception.BusinessException;

/**
 * STOMP 핸드셰이크 인증. {@code access_token} HttpOnly 쿠키의 JWT를 검증해 userId를 세션 attribute에 심는다
 * ({@link ChatHandshakeHandler}가 이를 읽어 Principal로 승격). 실패 시 401로 핸드셰이크를 거부한다
 * (설계서 §5, CHAT_WS_UNAUTHORIZED). 분기별로 {@code chat.ws.handshake} 카운터와 {@code chat.ws.handshake.duration}
 * Timer를 남겨 부하테스트 중 인증 실패율·소요시간을 Grafana에서 바로 볼 수 있게 한다.
 */
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

  /** 세션 attribute 키 — 인증된 userId(UUID)를 담는다. */
  public static final String USER_ID_ATTRIBUTE = "chatUserId";

  private static final String METRIC_NAME = "chat.ws.handshake";
  private static final String TIMER_NAME = "chat.ws.handshake.duration";
  private static final String RESULT_TAG = "result";

  private final CookieProvider cookieProvider;
  private final JwtProvider jwtProvider;
  private final MeterRegistry meterRegistry;

  @Override
  public boolean beforeHandshake(
      @NonNull ServerHttpRequest request,
      @NonNull ServerHttpResponse response,
      @NonNull WebSocketHandler wsHandler,
      @NonNull Map<String, Object> attributes) {
    Timer.Sample sample = Timer.start(meterRegistry);
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      recordResult(sample, "no_servlet_request");
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    Optional<String> token =
        cookieProvider.readCookie(servletRequest.getServletRequest(), CookieProvider.ACCESS_TOKEN_COOKIE);
    if (token.isEmpty()) {
      recordResult(sample, "no_token");
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    try {
      jwtProvider.validate(token.get());
      UUID userId = jwtProvider.getUserId(token.get());
      attributes.put(USER_ID_ATTRIBUTE, userId);
      recordResult(sample, "success");
      return true;
    } catch (BusinessException ex) {
      // 토큰 위조·만료 등 검증 실패 → 핸드셰이크 거부(401).
      recordResult(sample, "invalid_token");
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
  }

  private void recordResult(Timer.Sample sample, String result) {
    meterRegistry.counter(METRIC_NAME, RESULT_TAG, result).increment();
    sample.stop(meterRegistry.timer(TIMER_NAME, RESULT_TAG, result));
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
