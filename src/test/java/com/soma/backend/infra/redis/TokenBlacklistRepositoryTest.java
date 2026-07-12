package com.soma.backend.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

@DisplayName("TokenBlacklistRepository 단위 테스트")
class TokenBlacklistRepositoryTest {

  @SuppressWarnings("unchecked")
  private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
  private final TokenBlacklistRepository repository =
      new TokenBlacklistRepository(redisTemplate, 1_800_000L);

  @Test
  @DisplayName("Redis 장애 시 blacklist 조회는 fail-open으로 false를 반환한다")
  void isBlacklisted_redisDown_failsOpen() {
    // Given
    UUID userId = UUID.randomUUID();
    given(redisTemplate.hasKey("auth:blacklist:" + userId))
        .willThrow(new RedisConnectionFailureException("redis down"));

    // When & Then
    assertThat(repository.isBlacklisted(userId)).isFalse();
  }

  @Test
  @DisplayName("등록된 userId면 blacklist 조회가 true를 반환한다")
  void isBlacklisted_present_returnsTrue() {
    // Given
    UUID userId = UUID.randomUUID();
    given(redisTemplate.hasKey("auth:blacklist:" + userId)).willReturn(true);

    // When & Then
    assertThat(repository.isBlacklisted(userId)).isTrue();
  }
}
