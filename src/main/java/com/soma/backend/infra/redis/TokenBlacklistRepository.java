package com.soma.backend.infra.redis;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import lombok.extern.slf4j.Slf4j;

/**
 * 무효화된 access 토큰을 사용자 단위로 관리하는 blacklist. 회원 탈퇴 시 userId를 등록하면
 * 아직 만료되지 않은 access 토큰이라도 {@code JwtFilter}가 거부한다.
 *
 * <p>키는 {@code auth:blacklist:{userId}}, TTL은 access 토큰 수명({@code jwt.access-token-expiry})이다.
 * TTL이 지나면 유효한 access 토큰이 더는 남아있지 않으므로 엔트리는 자동 소멸한다. 특정 토큰(jti)이
 * 아니라 userId 단위라, 다기기에 발급된 access 토큰을 한 번에 무효화한다.
 */
@Slf4j
@Repository
public class TokenBlacklistRepository {

  private static final String PREFIX = "auth:blacklist:";
  private static final String BLACKLISTED = "1";

  private final RedisTemplate<String, String> redisTemplate;
  private final Duration ttl;

  public TokenBlacklistRepository(
      RedisTemplate<String, String> redisTemplate,
      @Value("${jwt.access-token-expiry}") long accessTokenExpiryMillis) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMillis(accessTokenExpiryMillis);
  }

  /**
   * 사용자의 access 토큰을 blacklist에 등록한다(회원 탈퇴). TTL 후 자동 소멸한다.
   */
  public void blacklist(UUID userId) {
    redisTemplate.opsForValue().set(key(userId), BLACKLISTED, ttl);
  }

  /**
   * 사용자가 blacklist에 등록돼 있는지 조회한다. 매 인증 요청에서 호출되므로 Redis 장애 시
   * fail-open으로 동작한다 — 전체 인증을 막는 것보다, 드물게 탈퇴 유저의 남은 access 토큰(최대 access
   * 만료)만 잠깐 유효해지는 편이 낫다.
   */
  public boolean isBlacklisted(UUID userId) {
    try {
      return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
    } catch (DataAccessException ex) {
      log.warn("blacklist 조회 실패, fail-open 처리 (userId={}): {}", userId, ex.getMessage());
      return false;
    }
  }

  private String key(UUID userId) {
    return PREFIX + userId;
  }
}
