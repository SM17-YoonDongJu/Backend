package com.soma.backend.infra.redis;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;

/**
 * 채팅 브로드캐스트 publisher. 메시지를 JSON 직렬화해 Redis 채널로 발행한다.
 *
 * <p>{@link #publishAfterCommit}는 현재 트랜잭션이 커밋된 뒤에만 발행하도록 동기화를 등록한다 —
 * DB 저장이 롤백되면 브로드캐스트도 나가지 않게 하기 위함이다(설계서 §5, afterCommit publish).
 */
@Component
@RequiredArgsConstructor
public class ChatEventPublisher {

  private static final String PUBLISH_METRIC = "chat.relay.publish";

  private final RedisTemplate<String, String> redisTemplate;
  private final JsonMapper jsonMapper;
  private final MeterRegistry meterRegistry;

  /** 트랜잭션 커밋 후 발행. 활성 트랜잭션이 없으면 즉시 발행한다. */
  public void publishAfterCommit(ChatBroadcastMessage message) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          publish(message);
        }
      });
    } else {
      publish(message);
    }
  }

  /** 즉시 발행(직렬화 후 convertAndSend). 실패해도 메시지 저장 자체는 이미 커밋된 상태다. */
  public void publish(ChatBroadcastMessage message) {
    redisTemplate.convertAndSend(RedisConfig.CHAT_MESSAGE_CHANNEL, jsonMapper.writeValueAsString(message));
    meterRegistry.counter(PUBLISH_METRIC).increment();
  }
}
