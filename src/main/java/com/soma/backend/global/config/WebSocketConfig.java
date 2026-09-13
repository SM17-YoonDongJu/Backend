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
import com.soma.backend.global.security.ChatStompErrorHandler;
import com.soma.backend.global.security.ChatSubscribeInterceptor;

/**
 * STOMP over WebSocket 설정(설계서 §5). 엔드포인트 {@code /ws-chat}(native WebSocket), SimpleBroker
 * {@code /topic}, 앱 prefix {@code /app}. 쿠키 기반 핸드셰이크 인증({@link ChatHandshakeInterceptor} +
 * {@link ChatHandshakeHandler})과 SUBSCRIBE 참여자 인가({@link ChatSubscribeInterceptor})를 배선한다.
 * 클라이언트는 구독만 하고 전송은 REST(③)라 {@code @MessageMapping}은 두지 않는다.
 *
 * <p><b>{@link ChatStompErrorHandler} 배선을 빼지 말 것.</b> 빼면 구독 거절이 세션 전체를 1002
 * {@code PROTOCOL_ERROR}로 끊어 같은 연결의 정상 구독까지 죽는다(Spring은 나가는 프레임 커맨드가 ERROR이면
 * 무조건 세션을 닫는다). Spring Boot가 이 타입의 빈을 자동 탐지하지 않으므로 명시 배선이 유일한 경로다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final ChatHandshakeInterceptor chatHandshakeInterceptor;
  private final ChatHandshakeHandler chatHandshakeHandler;
  private final ChatSubscribeInterceptor chatSubscribeInterceptor;
  private final ChatStompErrorHandler chatStompErrorHandler;

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
    // 엔드포인트별이 아니라 레지스트리(=StompSubProtocolHandler) 전역 설정이라 addEndpoint 체인에 붙이지 않는다.
    registry.setErrorHandler(chatStompErrorHandler);
    String[] origins = allowedOriginPatterns.toArray(new String[0]);
    registry.addEndpoint("/ws-chat")
        .setAllowedOriginPatterns(origins)
        .addInterceptors(chatHandshakeInterceptor)
        .setHandshakeHandler(chatHandshakeHandler);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(chatSubscribeInterceptor);
  }
}
