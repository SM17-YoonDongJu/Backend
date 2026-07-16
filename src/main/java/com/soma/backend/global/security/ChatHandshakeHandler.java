package com.soma.backend.global.security;

import java.security.Principal;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * 핸드셰이크에서 심어진 userId attribute를 STOMP 세션 Principal로 승격한다. 이후 SUBSCRIBE 인가·목적지 라우팅이
 * 이 Principal(name=userId)을 사용한다({@link ChatSubscribeInterceptor}).
 */
@Component
public class ChatHandshakeHandler extends DefaultHandshakeHandler {

  @Override
  protected @Nullable Principal determineUser(
      ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
    Object userId = attributes.get(ChatHandshakeInterceptor.USER_ID_ATTRIBUTE);
    if (userId == null) {
      return null;
    }
    return new ChatPrincipal(userId.toString());
  }

  /** STOMP 세션 사용자 — name에 userId(UUID) 문자열을 담는다. */
  private record ChatPrincipal(String userId) implements Principal {

    @Override
    public String getName() {
      return userId;
    }
  }
}
