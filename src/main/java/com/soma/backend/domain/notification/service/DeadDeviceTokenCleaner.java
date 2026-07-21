package com.soma.backend.domain.notification.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.repository.DeviceTokenRepository;

/**
 * 죽은 디바이스 토큰 정리를 독립 트랜잭션으로 수행하는 내부 협력자.
 *
 * <p>발송({@link PushNotificationService})이 FCM 네트워크 I/O를 트랜잭션 밖에서 수행하도록, 삭제(@Modifying)만
 * 이 빈의 짧은 트랜잭션으로 분리한다. 블로킹 발송이 DB 커넥션을 점유하는 것을 막기 위함이다. 같은 클래스 내
 * 호출은 프록시를 우회해 @Transactional이 적용되지 않으므로 반드시 별도 빈이어야 한다(DeviceTokenWriter와 동일).
 */
@Component
@RequiredArgsConstructor
class DeadDeviceTokenCleaner {

  private final DeviceTokenRepository deviceTokenRepository;

  @Transactional
  public void delete(UUID userId, List<String> deadTokens) {
    deviceTokenRepository.deleteByUserIdAndTokenIn(userId, deadTokens);
  }
}
