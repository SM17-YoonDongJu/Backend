package com.soma.backend.infra.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token을 Redis에 {@code refresh:{userId}} 키로 저장·조회·삭제한다.
 *
 * <p>TTL은 {@code jwt.refresh-token-expiry}(ms)를 재사용한다. RTR(Refresh Token Rotation)에서는
 * 재발급마다 {@link #save(UUID, String)}로 기존 값을 덮어써 이전 토큰을 무효화한다.
 */
@Repository
public class RefreshTokenRepository {

  private static final String PREFIX = "refresh:";

  private final RedisTemplate<String, String> redisTemplate;
  private final Duration ttl;

  public RefreshTokenRepository(
      RedisTemplate<String, String> redisTemplate,
      @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiryMillis) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMillis(refreshTokenExpiryMillis);
  }

  /**
   * Refresh Token을 저장한다. 기존 값이 있으면 덮어써(RTR) 무효화한다.
   */
  public void save(UUID userId, String refreshToken) {
    redisTemplate.opsForValue().set(key(userId), refreshToken, ttl);
  }

  /**
   * 저장된 Refresh Token을 조회한다. 없으면 {@link Optional#empty()}.
   */
  public Optional<String> find(UUID userId) {
    return Optional.ofNullable(redisTemplate.opsForValue().get(key(userId)));
  }

  /**
   * 저장된 Refresh Token을 삭제한다(로그아웃). 없어도 예외 없이 멱등하게 동작한다.
   */
  public void delete(UUID userId) {
    redisTemplate.delete(key(userId));
  }

  private String key(UUID userId) {
    return PREFIX + userId;
  }
}
