package com.soma.backend.infra.outbox;

import java.time.LocalDateTime;
import java.util.List;
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
  private static final String AGGREGATE_USER = "USER";

  private final OutboxEventRepository outboxEventRepository;
  private final JsonMapper jsonMapper;

  /**
   * 회원 탈퇴 후처리(Refresh 삭제·blacklist 등록·탈퇴 원장 기록) 이벤트를 적재한다. 호출자의 트랜잭션에
   * 참여하므로, 탈퇴 커밋이 성공한 경우에만 이벤트가 남는다.
   */
  public void publishAuthCleanup(UUID userId, List<SocialIdentity> socials) {
    AuthCleanupPayload payload = new AuthCleanupPayload(userId, socials);
    String json = jsonMapper.writeValueAsString(payload);
    outboxEventRepository.save(
        OutboxEvent.of(AGGREGATE_USER, userId, EVENT_AUTH_CLEANUP, json, LocalDateTime.now()));
  }
}
