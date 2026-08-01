package com.soma.backend.infra.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

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

import com.soma.backend.global.security.crypto.AesGcmCipher;
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

  @Mock
  private AesGcmCipher aesGcmCipher;

  @Mock
  private AppleTokenRevoker appleTokenRevoker;

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private OutboxProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new OutboxProcessor(outboxEventRepository, jsonMapper,
        refreshTokenRepository, tokenBlacklistRepository, aesGcmCipher, appleTokenRevoker);
  }

  private OutboxEvent authCleanupEvent(UUID userId) {
    String payload = jsonMapper.writeValueAsString(new AuthCleanupPayload(userId));
    return OutboxEvent.of("USER", userId, OutboxEventPublisher.EVENT_AUTH_CLEANUP, payload, NOW);
  }

  private OutboxEvent appleRevokeEvent(UUID userId, String encryptedRefreshToken) {
    String payload = jsonMapper.writeValueAsString(new AppleRevokePayload(encryptedRefreshToken));
    return OutboxEvent.of("USER", userId, OutboxEventPublisher.EVENT_APPLE_REVOKE, payload, NOW);
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

  @Test
  @DisplayName("APPLE_REVOKE 이벤트를 처리하면 암호문을 복호화해 revoke하고 PROCESSED로 표시한다")
  void poll_appleRevoke_decryptsAndRevokes() {
    // Given
    UUID userId = UUID.randomUUID();
    OutboxEvent event = appleRevokeEvent(userId, "enc-token");
    given(outboxEventRepository.lockPendingBatch(any(), anyInt())).willReturn(List.of(event));
    given(aesGcmCipher.decrypt("enc-token")).willReturn("plain-refresh-token");

    // When
    processor.poll();

    // Then
    then(appleTokenRevoker).should().revoke("plain-refresh-token");
    then(refreshTokenRepository).should(never()).delete(any());
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
  }

  @Test
  @DisplayName("APPLE_REVOKE revoke 호출이 실패하면 재시도 횟수를 늘리고 PENDING으로 남긴다")
  void poll_appleRevokeFails_schedulesRetry() {
    // Given
    UUID userId = UUID.randomUUID();
    OutboxEvent event = appleRevokeEvent(userId, "enc-token");
    given(outboxEventRepository.lockPendingBatch(any(), anyInt())).willReturn(List.of(event));
    given(aesGcmCipher.decrypt("enc-token")).willReturn("plain-refresh-token");
    willThrow(new RuntimeException("revoke 실패"))
        .given(appleTokenRevoker).revoke("plain-refresh-token");

    // When
    processor.poll();

    // Then
    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(event.getRetryCount()).isEqualTo(1);
  }
}
