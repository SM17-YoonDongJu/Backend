package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.notification.dto.NotificationListResponse;
import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

  @InjectMocks
  private NotificationService notificationService;

  @Mock
  private NotificationRepository notificationRepository;

  private Notification sampleNotification(UUID userId) {
    return Notification.create(userId, NotificationType.REVIEW_COMPLETE, "검수 완료", "리포트 검수가 완료되었습니다.");
  }

  @Test
  @DisplayName("getMyNotifications-목록과 미읽음 수를 함께 반환한다")
  void getMyNotifications_returnsItemsAndUnreadCount() {
    // Given
    UUID userId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    Page<Notification> page = new PageImpl<>(List.of(sampleNotification(userId)), pageable, 1);
    given(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)).willReturn(page);
    given(notificationRepository.countByUserIdAndIsReadFalse(userId)).willReturn(1L);

    // When
    NotificationListResponse result = notificationService.getMyNotifications(userId, pageable);

    // Then
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).type()).isEqualTo("REVIEW_COMPLETE");
    assertThat(result.items().get(0).isRead()).isFalse();
    assertThat(result.unreadCount()).isEqualTo(1L);
    assertThat(result.totalElements()).isEqualTo(1L);
  }

  @Test
  @DisplayName("markAllRead-미읽음 알림을 일괄 읽음 처리한다")
  void markAllRead_bulkUpdates() {
    // Given
    UUID userId = UUID.randomUUID();

    // When
    notificationService.markAllRead(userId);

    // Then
    then(notificationRepository).should().markAllReadByUserId(userId);
  }

  @Test
  @DisplayName("markRead-본인 소유 알림이면 읽음으로 전이한다")
  void markRead_owned_marksRead() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID notificationId = UUID.randomUUID();
    Notification notification = sampleNotification(userId);
    given(notificationRepository.findByIdAndUserId(notificationId, userId))
        .willReturn(Optional.of(notification));

    // When
    notificationService.markRead(userId, notificationId);

    // Then
    assertThat(notification.isRead()).isTrue();
  }

  @Test
  @DisplayName("markRead-없거나 타인 소유면 NOTIFICATION_NOT_FOUND를 던진다")
  void markRead_notOwned_throws() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID notificationId = UUID.randomUUID();
    given(notificationRepository.findByIdAndUserId(notificationId, userId))
        .willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> notificationService.markRead(userId, notificationId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
  }
}
