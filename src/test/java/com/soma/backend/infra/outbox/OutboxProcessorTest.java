package com.soma.backend.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import tools.jackson.databind.json.JsonMapper;

import com.soma.backend.infra.redis.RefreshTokenRepository;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxProcessor 단위 테스트")
class OutboxProcessorTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);

  @Mock
  private OutboxEventRepository outboxEventRepository;

  @Mock
  private RefreshTokenRepository refreshTokenRepository;

  @Mock
  private TokenBlacklistRepository tokenBlacklistRepository;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private OutboxProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new OutboxProcessor(outboxEventRepository, jsonMapper,
        refreshTokenRepository, tokenBlacklistRepository);
  }

  private OutboxEvent authCleanupEvent(UUID userId) {
    String payload = jsonMapper.writeValueAsString(new AuthCleanupPayload(userId));
    return OutboxEvent.of("USER", userId, OutboxEventPublisher.EVENT_AUTH_CLEANUP, payload, NOW);
  }

  @Test
  @DisplayName("AUTH_CLEANUP 이벤트를 처리하면 Redis 부수효과를 실행하고 PROCESSED로 표시한다")
  void poll_authCleanup_runsSideEffectsAndMarksProcessed() {
    // Given
    UUID userId = UUID.randomUUID();
    OutboxEvent event = authCleanupEvent(userId);
    given(outboxEventRepository.lockPendingBatch(any(), anyInt())).willReturn(List.of(event));

    // When
    processor.poll();

    // Then
    then(refreshTokenRepository).should().delete(userId);
    then(tokenBlacklistRepository).should().blacklist(userId);
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
  }

  @Test
  @DisplayName("처리 중 예외가 나면 재시도 횟수를 늘리고 PENDING으로 남겨 재시도를 예약한다")
  void poll_handlerFails_schedulesRetry() {
    // Given
    UUID userId = UUID.randomUUID();
    OutboxEvent event = authCleanupEvent(userId);
    given(outboxEventRepository.lockPendingBatch(any(), anyInt())).willReturn(List.of(event));
    willThrow(new RedisConnectionFailureException("redis down"))
        .given(refreshTokenRepository).delete(userId);

    // When
    processor.poll();

    // Then
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event.getRetryCount()).isEqualTo(1);
  }
}
