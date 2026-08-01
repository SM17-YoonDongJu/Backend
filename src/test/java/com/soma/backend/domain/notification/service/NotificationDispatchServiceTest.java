package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.notification.entity.Notification;
import com.soma.backend.domain.notification.entity.NotificationSetting;
import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.notification.repository.NotificationRepository;
import com.soma.backend.domain.notification.repository.NotificationSettingRepository;

/**
 * NotificationDispatchService.record() 단위 테스트.
 * 인앱 알림은 토글과 무관하게 항상 저장하고, 반환값(푸시 허용 여부)만 토글이 게이팅한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatchService 단위 테스트")
class NotificationDispatchServiceTest {

  private static final String TITLE = "새로운 제안이 도착했어요";
  private static final String BODY = "손해사정사가 새로운 검수 제안을 보냈어요. 제안을 비교해보세요.";

  @InjectMocks
  private NotificationDispatchService notificationDispatchService;
  @Mock
  private NotificationRepository notificationRepository;
  @Mock
  private NotificationSettingRepository notificationSettingRepository;

  @Test
  @DisplayName("record는 Notification.create로 인앱 알림을 저장하고 토글 ON이면 true를 반환한다")
  void recordSavesInAppAndReturnsAllowedWhenToggleOn() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationSettingRepository.findById(userId))
        .willReturn(Optional.of(NotificationSetting.createDefault(userId))); // received_proposal 기본 ON

    // When
    boolean pushAllowed =
        notificationDispatchService.record(userId, NotificationType.RECEIVED_PROPOSAL, TITLE, BODY);

    // Then
    assertThat(pushAllowed).isTrue();
    ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
    verify(notificationRepository).save(captor.capture());
    Notification saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getType()).isEqualTo(NotificationType.RECEIVED_PROPOSAL);
    assertThat(saved.getTitle()).isEqualTo(TITLE);
    assertThat(saved.getBody()).isEqualTo(BODY);
    assertThat(saved.isRead()).isFalse();
  }

  @Test
  @DisplayName("토글 OFF여도 인앱 알림은 저장하고 false(푸시 억제)를 반환한다")
  void recordSavesInAppButReturnsFalseWhenToggleOff() {
    // Given
    UUID userId = UUID.randomUUID();
    NotificationSetting off = NotificationSetting.createDefault(userId);
    ReflectionTestUtils.setField(off, "receivedProposal", false);
    given(notificationSettingRepository.findById(userId)).willReturn(Optional.of(off));

    // When
    boolean pushAllowed =
        notificationDispatchService.record(userId, NotificationType.RECEIVED_PROPOSAL, TITLE, BODY);

    // Then
    assertThat(pushAllowed).isFalse();
    verify(notificationRepository).save(any(Notification.class)); // 인앱은 항상 저장
  }

  @Test
  @DisplayName("설정이 없으면 createDefault를 저장한 뒤 그 설정의 allows(type)를 반환한다")
  void recordCreatesDefaultSettingWhenAbsent() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationSettingRepository.findById(userId)).willReturn(Optional.empty());
    given(notificationSettingRepository.save(any(NotificationSetting.class)))
        .willAnswer(inv -> inv.getArgument(0));

    // When
    boolean pushAllowed =
        notificationDispatchService.record(userId, NotificationType.RECEIVED_PROPOSAL, TITLE, BODY);

    // Then — createDefault의 received_proposal 기본 ON
    assertThat(pushAllowed).isTrue();
    ArgumentCaptor<NotificationSetting> captor = ArgumentCaptor.forClass(NotificationSetting.class);
    verify(notificationSettingRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    verify(notificationRepository).save(any(Notification.class));
  }
}
