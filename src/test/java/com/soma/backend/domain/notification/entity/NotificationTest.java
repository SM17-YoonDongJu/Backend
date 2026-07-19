package com.soma.backend.domain.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Notification 엔티티 단위 테스트")
class NotificationTest {

  @Test
  @DisplayName("create-미읽음(false) 상태로 생성된다")
  void create_startsUnread() {
    // Given
    UUID userId = UUID.randomUUID();

    // When
    Notification notification =
        Notification.create(userId, NotificationType.NEW_REVIEW_REQUEST, "새 검수 요청", null);

    // Then
    assertThat(notification.getUserId()).isEqualTo(userId);
    assertThat(notification.getType()).isEqualTo(NotificationType.NEW_REVIEW_REQUEST);
    assertThat(notification.getTitle()).isEqualTo("새 검수 요청");
    assertThat(notification.getBody()).isNull();
    assertThat(notification.isRead()).isFalse();
  }

  @Test
  @DisplayName("markRead-읽음으로 전이하고 여러 번 호출해도 멱등하다")
  void markRead_idempotent() {
    // Given
    Notification notification =
        Notification.create(UUID.randomUUID(), NotificationType.CHAT_MESSAGE, "새 메시지", "안녕하세요");

    // When
    notification.markRead();
    notification.markRead();

    // Then
    assertThat(notification.isRead()).isTrue();
  }

  @Test
  @DisplayName("isOwnedBy-수신자 본인이면 true, 아니면 false")
  void isOwnedBy_checksOwner() {
    // Given
    UUID userId = UUID.randomUUID();
    Notification notification =
        Notification.create(userId, NotificationType.SETTLEMENT_NOTICE, "합의 안내", null);

    // Then
    assertThat(notification.isOwnedBy(userId)).isTrue();
    assertThat(notification.isOwnedBy(UUID.randomUUID())).isFalse();
  }
}
