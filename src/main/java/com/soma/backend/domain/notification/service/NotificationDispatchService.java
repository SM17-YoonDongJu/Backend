package com.soma.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.entity.NotificationSetting;
import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.domain.notification.repository.NotificationSettingRepository;

/**
 * 인앱 알림 저장 + 푸시 허용 여부(토글) 판정. AFTER_COMMIT 리스너에서 호출돼 활성 트랜잭션이 없으므로
 * 새 트랜잭션(REQUIRES_NEW)에서 insert한다.
 *
 * <p>토글이 OFF여도 인앱 알림함(NOTIFICATIONS)에는 항상 저장하고, 토글은 푸시(디바이스 발송) 여부만 게이팅한다.
 * {@code NotificationSettingService.getMySettings}(Response DTO 반환)는 엔티티 토글이 직접 필요해 재사용하지 않고,
 * 설정이 없으면 {@link NotificationSetting#createDefault(UUID)}로 만들어 저장한 뒤 {@code allows(type)}를 읽는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

  private final NotificationRepository notificationRepository;
  private final NotificationSettingRepository notificationSettingRepository;

  /**
   * 인앱 알림을 저장하고 "푸시 허용 여부(토글)"를 반환한다. 토글 OFF여도 인앱은 항상 저장(푸시만 억제).
   *
   * @return 해당 type의 푸시 발송이 허용되면 {@code true}
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean record(UUID userId, NotificationType type, String title, String body) {
    notificationRepository.save(Notification.create(userId, type, title, body));
    NotificationSetting setting = notificationSettingRepository.findById(userId)
        .orElseGet(() -> notificationSettingRepository.save(NotificationSetting.createDefault(userId)));
    return setting.allows(type);
  }
}
