package com.soma.backend.global.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * STOMP 세션 생명주기 이벤트({@code org.springframework.web.socket.messaging})를 구독해 현재 연결·구독
 * 수를 Gauge로, 연결 종료 사유를 Counter로 노출한다. 기존 {@link ChatHandshakeInterceptor}·
 * {@link ChatSubscribeInterceptor}는 건드리지 않고 옆에서 이벤트만 듣는다.
 *
 * <p>클라이언트가 UNSUBSCRIBE 없이 브라우저 탭을 그냥 닫는 경우 {@link SessionDisconnectEvent}만
 * 발생하므로, 세션별 구독 수를 따로 추적해뒀다가 연결 종료 시 그만큼 활성 구독 수에서 빼야
 * 음수로 새지 않는다.
 */
@Component
public class ChatWebSocketSessionMetrics {

  private static final String CONNECTIONS_METRIC = "chat.ws.connections.active";
  private static final String SUBSCRIPTIONS_METRIC = "chat.ws.subscriptions.active";
  private static final String DISCONNECT_METRIC = "chat.ws.disconnect";
  private static final String STATUS_TAG = "status";

  private final AtomicInteger activeConnections = new AtomicInteger();
  private final AtomicInteger activeSubscriptions = new AtomicInteger();
  private final Map<String, AtomicInteger> subscriptionsBySession = new ConcurrentHashMap<>();
  private final MeterRegistry meterRegistry;

  public ChatWebSocketSessionMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge(CONNECTIONS_METRIC, activeConnections);
    meterRegistry.gauge(SUBSCRIPTIONS_METRIC, activeSubscriptions);
  }

  @EventListener
  public void onConnected(SessionConnectedEvent event) {
    activeConnections.incrementAndGet();
  }

  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    activeConnections.decrementAndGet();
    meterRegistry.counter(DISCONNECT_METRIC, STATUS_TAG, String.valueOf(event.getCloseStatus().getCode()))
        .increment();
    AtomicInteger sessionSubscriptions = subscriptionsBySession.remove(event.getSessionId());
    if (sessionSubscriptions != null) {
      activeSubscriptions.addAndGet(-sessionSubscriptions.get());
    }
  }

  @EventListener
  public void onSubscribe(SessionSubscribeEvent event) {
    activeSubscriptions.incrementAndGet();
    String sessionId = SimpMessageHeaderAccessor.wrap(event.getMessage()).getSessionId();
    subscriptionsBySession.computeIfAbsent(sessionId, key -> new AtomicInteger()).incrementAndGet();
  }

  @EventListener
  public void onUnsubscribe(SessionUnsubscribeEvent event) {
    activeSubscriptions.decrementAndGet();
    String sessionId = SimpMessageHeaderAccessor.wrap(event.getMessage()).getSessionId();
    AtomicInteger sessionSubscriptions = subscriptionsBySession.get(sessionId);
    if (sessionSubscriptions != null) {
      sessionSubscriptions.decrementAndGet();
    }
  }
}
