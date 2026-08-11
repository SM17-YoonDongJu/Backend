package com.soma.backend.infra.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아웃박스에 이벤트를 적재하는 진입점. 도메인 서비스의 트랜잭션 안에서 호출돼 도메인 변경과 원자적으로
 * 커밋된다. 실제 부수효과는 {@link OutboxProcessor}가 커밋 이후 처리한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

  public static final String EVENT_AUTH_CLEANUP = "AUTH_CLEANUP";
  public static final String EVENT_APPLE_REVOKE = "APPLE_REVOKE";
  private static final String AGGREGATE_USER = "USER";

  private final OutboxEventRepository outboxEventRepository;
  private final JsonMapper jsonMapper;

  /**
   * 회원 탈퇴 후처리(Refresh 삭제·blacklist 등록) 이벤트를 적재한다. 호출자의 트랜잭션에
   * 참여하므로, 탈퇴 커밋이 성공한 경우에만 이벤트가 남는다.
   */
  public void publishAuthCleanup(UUID userId) {
    AuthCleanupPayload payload = new AuthCleanupPayload(userId);
    String json = jsonMapper.writeValueAsString(payload);
    outboxEventRepository.save(
        OutboxEvent.of(AGGREGATE_USER, userId, EVENT_AUTH_CLEANUP, json, LocalDateTime.now()));
  }

  /**
   * 탈퇴한 회원의 Apple refresh_token(암호문) revoke 이벤트를 적재한다. 호출자의 트랜잭션에 참여하므로
   * 탈퇴 커밋이 성공한 경우에만 이벤트가 남고, 소비자가 복호화 후 Apple revoke를 재시도하며 처리한다.
   * payload에는 암호문과 AAD 재구성용 행 식별자(provider, providerUserId)만 실어 평문이 아웃박스에
   * 남지 않는다.
   */
  public void publishAppleRevoke(
      UUID userId, String provider, String providerUserId, String encryptedRefreshToken) {
    AppleRevokePayload payload = new AppleRevokePayload(encryptedRefreshToken, provider, providerUserId);
    String json = jsonMapper.writeValueAsString(payload);
    outboxEventRepository.save(
        OutboxEvent.of(AGGREGATE_USER, userId, EVENT_APPLE_REVOKE, json, LocalDateTime.now()));
  }
}
