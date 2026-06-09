package com.soma.backend.domain.chat.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * WebSocket 온라인 상태 추적 서비스.
 * Redis SET(SADD/SREM)으로 사용자별 활성 세션을 관리한다(다중 디바이스 지원).
 * 키: ws:online:{userId}, TTL 안전망 1시간(DISCONNECT 이벤트 누락 대비).
 */
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String PREFIX = "ws:online:";
    private static final long TTL_SECONDS = 3600L;

    public void connect(UUID userId, String sessionId) {
        String key = PREFIX + userId;
        redisTemplate.opsForSet().add(key, sessionId);
        redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
    }

    public void disconnect(UUID userId, String sessionId) {
        redisTemplate.opsForSet().remove(PREFIX + userId, sessionId);
    }

    public boolean isOnline(UUID userId) {
        Long size = redisTemplate.opsForSet().size(PREFIX + userId);
        return size != null && size > 0;
    }
}
