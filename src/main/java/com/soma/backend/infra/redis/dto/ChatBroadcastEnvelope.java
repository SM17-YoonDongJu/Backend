package com.soma.backend.infra.redis.dto;

import java.time.Instant;

/**
 * Redis 채널에 실제로 실리는 wire 포맷. {@link ChatBroadcastMessage}에 발행 시각을 얹어
 * {@link com.soma.backend.infra.redis.ChatMessageSubscriber}가 발행→relay 종단 지연을 계산할 수 있게 한다.
 *
 * <p>publish/subscribe 두 클래스만 이 타입을 알고, {@link ChatBroadcastMessage}를 만드는 도메인 서비스들은
 * 이 envelope의 존재를 모른다 — relay 구간 관측은 순전히 infra 레이어의 관심사이기 때문이다.
 */
public record ChatBroadcastEnvelope(ChatBroadcastMessage message, Instant publishedAt) {
}
