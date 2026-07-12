package com.soma.backend.infra.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token을 Redis에 {@code refresh:{userId}} 키로 저장·회전·삭제한다.
 *
 * <p>TTL은 {@code jwt.refresh-token-expiry}(ms)를 재사용한다. RTR(Refresh Token Rotation) 재발급은
 * {@link #rotate(UUID, String, String)}가 Lua 스크립트로 검증-교체를 원자적으로 수행해 동시 재발급
 * 경쟁 창(race window)을 제거한다. 최초·소셜 로그인은 {@link #save(UUID, String)}로 덮어쓴다.
 */
@Repository
public class RefreshTokenRepository {

  private static final String PREFIX = "refresh:";
  private static final String ROTATE_LUA =
      "local current = redis.call('GET', KEYS[1]) "
      + "if current == false then return -1 end "
      + "if current == ARGV[1] then "
      + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 end "
      + "redis.call('DEL', KEYS[1]) return 0";
  private static final RedisScript<Long> ROTATE_SCRIPT = RedisScript.of(ROTATE_LUA, Long.class);

  private final RedisTemplate<String, String> redisTemplate;
  private final Duration ttl;

  public RefreshTokenRepository(
      RedisTemplate<String, String> redisTemplate,
      @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiryMillis) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMillis(refreshTokenExpiryMillis);
  }

  /**
   * Refresh Token을 저장한다. 기존 값이 있으면 덮어써(RTR) 무효화한다. 최초·소셜 로그인용.
   */
  public void save(UUID userId, String refreshToken) {
    redisTemplate.opsForValue().set(key(userId), refreshToken, ttl);
  }

  /**
   * RTR 재발급을 원자적으로 수행한다. 저장값이 {@code oldToken}과 정확히 일치할 때만 {@code newToken}으로
   * 교체(CAS)하므로 동시 재발급 시 한 요청만 성공한다. 단일 원자 연산이라 삭제-저장 공백이 없어 토큰 유실도 없다.
   *
   * <ul>
   *   <li>저장값 == oldToken → newToken으로 교체 후 {@link RotateResult#ROTATED}</li>
   *   <li>저장값 없음(만료·미존재) → {@link RotateResult#NOT_FOUND}</li>
   *   <li>저장값 불일치(이미 회전됨·탈취 의심) → 해당 키 삭제 후 {@link RotateResult#MISMATCH}</li>
   * </ul>
   */
  public RotateResult rotate(UUID userId, String oldToken, String newToken) {
    Long result = redisTemplate.execute(
        ROTATE_SCRIPT, List.of(key(userId)), oldToken, newToken, String.valueOf(ttl.toMillis()));
    if (result != null && result == 1L) {
      return RotateResult.ROTATED;
    }
    if (result != null && result == 0L) {
      return RotateResult.MISMATCH;
    }
    return RotateResult.NOT_FOUND;
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

  /** {@link #rotate(UUID, String, String)} 결과 상태. */
  public enum RotateResult {
    ROTATED,
    NOT_FOUND,
    MISMATCH
  }
}
