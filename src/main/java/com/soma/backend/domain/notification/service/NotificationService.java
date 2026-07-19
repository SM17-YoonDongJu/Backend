package com.soma.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.NotificationListResponse;
import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 인앱 알림함 유스케이스. 모든 동작은 principal 본인(userId) 소유 알림만 대상으로 한다.
 * 알림 생성(producer)은 별도 티켓 범위이며, 여기서는 조회·읽음 처리만 다룬다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;

  /** 내 알림 목록(최신순)과 미읽음 수를 함께 조회한다. */
  @Transactional(readOnly = true)
  public NotificationListResponse getMyNotifications(UUID userId, Pageable pageable) {
    Page<Notification> page = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    long unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
    return NotificationListResponse.from(page, unreadCount);
  }

  /** 내 미읽음 알림을 모두 읽음 처리한다(벌크 UPDATE). */
  @Transactional
  public void markAllRead(UUID userId) {
    notificationRepository.markAllReadByUserId(userId);
  }

  /**
   * 내 알림 1건을 읽음 처리한다. 존재하지 않거나 본인 소유가 아니면 {@code NOTIFICATION_NOT_FOUND}(404).
   * 소유 판정을 (id, user_id) 조회로 하므로 타인 알림 id는 존재 자체를 노출하지 않는다.
   */
  @Transactional
  public void markRead(UUID userId, UUID notificationId) {
    Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));
    notification.markRead();
  }
}
