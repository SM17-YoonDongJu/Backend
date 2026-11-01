package com.soma.backend.infra.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

  /** 채팅 브로드캐스트 pub/sub 채널명. 전송 서버는 이 채널로 publish, 전 인스턴스가 구독해 STOMP로 relay 한다. */
  public static final String CHAT_MESSAGE_CHANNEL = "chat.message";

  @Value("${spring.data.redis.host}")
  private String host;

  @Value("${spring.data.redis.port}")
  private int port;

  @Bean
  public RedisConnectionFactory redisConnectionFactory() {
    return new LettuceConnectionFactory(host, port);
  }

  @Bean
  public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    // 해시(PendingReviewCountCache)도 문자열로 저장한다. 지정하지 않으면 JDK 직렬화가 기본이라 redis-cli로 읽을 수 없다.
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new StringRedisSerializer());
    return template;
  }

  /**
   * 채팅 브로드캐스트 구독 컨테이너. {@link #CHAT_MESSAGE_CHANNEL} 채널을 구독해 다른(또는 같은) 인스턴스가
   * publish 한 메시지를 {@link ChatMessageSubscriber}가 받아 STOMP {@code /topic}으로 relay 한다.
   */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory factory, ChatMessageSubscriber chatMessageSubscriber) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    container.addMessageListener(chatMessageSubscriber, new ChannelTopic(CHAT_MESSAGE_CHANNEL));
    return container;
  }
}
