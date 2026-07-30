package com.soma.backend.infra.redis;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link AppleRefreshStagingRepository}의 스테이징·소비 동작 검증. RedisTemplate을 목킹해 키·TTL·get→del
 * 흐름을 실제 Redis 없이 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AppleRefreshStagingRepositoryTest {

  private static final long TTL_MILLIS = 600000L;
  private static final String KEY = "apple:refresh:apple:apple-1";

  @Mock
  private RedisTemplate<String, String> redisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  private AppleRefreshStagingRepository repository;

  @BeforeEach
  void setUp() {
    repository = new AppleRefreshStagingRepository(redisTemplate, TTL_MILLIS);
  }

  @Test
  void stageSetsValueWithTtl() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);

    repository.stage("apple", "apple-1", "enc-token");

    then(valueOperations).should().set(KEY, "enc-token", Duration.ofMillis(TTL_MILLIS));
  }

  @Test
  void consumeReturnsValueViaGetAndDelete() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    given(valueOperations.getAndDelete(KEY)).willReturn("enc-token");

    Optional<String> result = repository.consume("apple", "apple-1");

    Assertions.assertThat(result).contains("enc-token");
  }

  @Test
  void consumeAbsentReturnsEmpty() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    given(valueOperations.getAndDelete(KEY)).willReturn(null);

    Optional<String> result = repository.consume("apple", "apple-1");

    Assertions.assertThat(result).isEmpty();
  }
}
