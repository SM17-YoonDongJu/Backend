package com.soma.backend.infra.redis;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.infra.redis.dto.ChatBroadcastEnvelope;
import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;

/**
 * Redis 채팅 채널 구독자. 발행된 브로드캐스트를 역직렬화해 해당 방의 STOMP 목적지로 relay 한다.
 *
 * <p>전송 서버 자신을 포함한 모든 인스턴스가 이 relay를 통해서만 브로드캐스트하므로, 각 세션은 메시지를 정확히
 * 한 번 수신한다(설계서 §5). 클라이언트는 구독만 하며 {@code sender_id}로 본인 메시지 여부를 판별한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageSubscriber implements MessageListener {

  private static final String ROOM_TOPIC_PREFIX = "/topic/chat.rooms.";
  private static final String DELIVER_METRIC = "chat.relay.deliver";
  private static final String LATENCY_METRIC = "chat.relay.latency";
  private static final String RESULT_TAG = "result";

  private final SimpMessagingTemplate messagingTemplate;
  private final JsonMapper jsonMapper;
  private final MeterRegistry meterRegistry;

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      ChatBroadcastEnvelope envelope = jsonMapper.readValue(message.getBody(), ChatBroadcastEnvelope.class);
      ChatBroadcastMessage payload = envelope.message();
      messagingTemplate.convertAndSend(
          ROOM_TOPIC_PREFIX + payload.roomId(), jsonMapper.writeValueAsString(payload));
      meterRegistry.counter(DELIVER_METRIC, RESULT_TAG, "success").increment();
      meterRegistry.timer(LATENCY_METRIC).record(Duration.between(envelope.publishedAt(), Instant.now()));
    } catch (RuntimeException ex) {
      // relay 실패는 실시간 전달만 영향 — 메시지는 이미 DB에 영속화됐으므로 로그만 남기고 삼킨다.
      meterRegistry.counter(DELIVER_METRIC, RESULT_TAG, "failure").increment();
      log.warn("채팅 브로드캐스트 relay 실패", ex);
    }
  }
}
