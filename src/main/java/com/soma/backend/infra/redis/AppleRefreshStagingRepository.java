package com.soma.backend.infra.redis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Apple refresh_token(암호문)을 콜백→가입 갭 동안 잠깐 보관하는 스테이징 저장소.
 *
 * <p>신규 유저는 OAuth 콜백 시점엔 SocialAccount가 없다(가입은 별도 요청). 콜백에서 확보한 refresh_token을
 * 이 저장소에 브릿지해 두고, 가입 완료 시 {@link #consume}로 꺼내 SocialAccount에 옮긴다.
 *
 * <p>키는 {@code apple:refresh:{provider}:{providerUserId}}, TTL은
 * {@code app.oauth.apple.refresh-staging-ttl-ms}(기본 10분, 가입 티켓 5분보다 길다). 저장값은 이미
 * 암호문이므로 이 저장소는 암복호화를 하지 않는다.
 */
@Repository
public class AppleRefreshStagingRepository {

  private static final String PREFIX = "apple:refresh:";

  private final RedisTemplate<String, String> redisTemplate;
  private final Duration ttl;

  public AppleRefreshStagingRepository(
      RedisTemplate<String, String> redisTemplate,
      @Value("${app.oauth.apple.refresh-staging-ttl-ms:600000}") long stagingTtlMillis) {
    this.redisTemplate = redisTemplate;
    this.ttl = Duration.ofMillis(stagingTtlMillis);
  }

  /**
   * 암호문 refresh_token을 TTL과 함께 스테이징한다. 재콜백 시 최신 값으로 덮어쓴다.
   */
  public void stage(String provider, String providerUserId, String encryptedRefreshToken) {
    redisTemplate.opsForValue().set(key(provider, providerUserId), encryptedRefreshToken, ttl);
  }

  /**
   * 스테이징된 암호문을 조회하고 삭제한다(get→del). 없으면 {@link Optional#empty()}.
   */
  public Optional<String> consume(String provider, String providerUserId) {
    String redisKey = key(provider, providerUserId);
    String value = redisTemplate.opsForValue().get(redisKey);
    if (value == null) {
      return Optional.empty();
    }
    redisTemplate.delete(redisKey);
    return Optional.of(value);
  }

  private String key(String provider, String providerUserId) {
    return PREFIX + provider + ":" + providerUserId;
  }
}
