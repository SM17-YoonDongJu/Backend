package com.soma.backend.domain.notification.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;
import com.soma.backend.infra.fcm.FcmService;

/**
 * 디바이스 푸시 발송 오케스트레이터. userId의 모든 디바이스 토큰으로 푸시를 보낸 뒤,
 * FcmService가 죽었다고 판정한 토큰(UNREGISTERED/INVALID_ARGUMENT)을 정리한다.
 *
 * <p>DeviceToken 수명주기(삭제)를 소유 도메인(notification)에 두어 infra(FcmService)를 leaf로 유지한다
 * — FcmService는 발송만 하고 DB를 모른다. 실제 호출부(producer: 검수 완료·매칭 결과 등 이벤트)는 다음
 * 단계 범위이며, 발송은 커밋과 무관한 부수효과이므로 AFTER_COMMIT 리스너에서 이 메서드를 호출하도록 설계한다.
 */
@Service
@RequiredArgsConstructor
public class PushNotificationService {

  private final DeviceTokenRepository deviceTokenRepository;
  private final FcmService fcmService;

  /** userId의 모든 토큰으로 푸시 발송 후 죽은 토큰을 정리한다. 대상 토큰이 없으면 아무 것도 하지 않는다. */
  @Transactional
  public void sendToUser(UUID userId, String title, String body, Map<String, String> data) {
    List<String> tokens = deviceTokenRepository.findByUserId(userId).stream()
        .map(DeviceToken::getToken)
        .toList();
    if (tokens.isEmpty()) {
      return;
    }
    List<String> deadTokens = fcmService.send(tokens, title, body, data);
    if (!deadTokens.isEmpty()) {
      deviceTokenRepository.deleteByUserIdAndTokenIn(userId, deadTokens);
    }
  }
}
