package com.soma.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.DeviceTokenResponse;
import com.soma.backend.domain.notification.dto.RegisterDeviceTokenRequest;
import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;

/**
 * 디바이스 토큰 신규 저장을 독립 트랜잭션(REQUIRES_NEW)으로 수행하는 내부 협력자.
 *
 * <p>별도 빈으로 분리한 이유: 동시 등록 경쟁 시 UNIQUE 위반이 나면 <b>이 트랜잭션만</b> 롤백되고
 * 예외가 호출자로 전파돼야, 호출자(DeviceTokenService)의 바깥 트랜잭션이 오염되지 않은 채 재조회로
 * 흡수할 수 있다. 같은 서비스 내 self-invocation은 프록시를 우회해 REQUIRES_NEW가 적용되지 않으므로
 * 반드시 다른 빈이어야 한다. saveAndFlush로 INSERT를 즉시 실행해, 위반을 커밋이 아닌 이 메서드 안에서
 * 터뜨린다(커밋 지연 시 바깥 핸들러로 새어 나가 409가 되는 것을 방지).
 */
@Component
@RequiredArgsConstructor
class DeviceTokenWriter {

  private final DeviceTokenRepository deviceTokenRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeviceTokenResponse insert(UUID userId, RegisterDeviceTokenRequest request) {
    DeviceToken deviceToken = DeviceToken.create(userId, request.token(), request.platform());
    DeviceToken saved = deviceTokenRepository.saveAndFlush(deviceToken);
    return DeviceTokenResponse.from(saved);
  }
}
