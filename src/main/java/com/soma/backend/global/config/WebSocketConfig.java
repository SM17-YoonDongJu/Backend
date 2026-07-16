package com.soma.backend.global.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.security.ChatHandshakeHandler;
import com.soma.backend.global.security.ChatHandshakeInterceptor;
import com.soma.backend.global.security.ChatSubscribeInterceptor;

/**
 * STOMP over WebSocket 설정(설계서 §5). 엔드포인트 {@code /ws-chat}(native + SockJS 폴백), SimpleBroker
 * {@code /topic}, 앱 prefix {@code /app}. 쿠키 기반 핸드셰이크 인증({@link ChatHandshakeInterceptor} +
 * {@link ChatHandshakeHandler})과 SUBSCRIBE 참여자 인가({@link ChatSubscribeInterceptor})를 배선한다.
 * 클라이언트는 구독만 하고 전송은 REST(③)라 {@code @MessageMapping}은 두지 않는다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final ChatHandshakeInterceptor chatHandshakeInterceptor;
  private final ChatHandshakeHandler chatHandshakeHandler;
  private final ChatSubscribeInterceptor chatSubscribeInterceptor;

  /** 쿠키 인증 핸드셰이크의 허용 오리진(앱 CORS 패턴 재사용). */
  @Value("${app.cors.allowed-origin-patterns}")
  private List<String> allowedOriginPatterns;

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    String[] origins = allowedOriginPatterns.toArray(new String[0]);
    registry.addEndpoint("/ws-chat")
        .setAllowedOriginPatterns(origins)
        .addInterceptors(chatHandshakeInterceptor)
        .setHandshakeHandler(chatHandshakeHandler);
    registry.addEndpoint("/ws-chat")
        .setAllowedOriginPatterns(origins)
        .addInterceptors(chatHandshakeInterceptor)
        .setHandshakeHandler(chatHandshakeHandler)
        .withSockJS();
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(chatSubscribeInterceptor);
  }
}
