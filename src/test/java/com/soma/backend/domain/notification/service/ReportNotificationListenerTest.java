package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.notification.entity.NotificationType;
import com.soma.backend.domain.report.entity.event.ReviewProposalReceivedEvent;

/**
 * ReportNotificationListener 단위 테스트.
 * AFTER_COMMIT 리스너는 record()로 인앱 저장·토글을 위임하고, 토글 ON일 때만 푸시하며,
 * 부수효과 실패(record·push 예외)는 삼켜 원 트랜잭션(이미 커밋)에 전파하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportNotificationListener 단위 테스트")
class ReportNotificationListenerTest {

  private static final String EXPECTED_TITLE = "새로운 제안이 도착했어요";
  private static final String EXPECTED_BODY = "손해사정사가 새로운 검수 제안을 보냈어요. 제안을 비교해보세요.";

  @InjectMocks
  private ReportNotificationListener listener;
  @Mock
  private NotificationDispatchService notificationDispatchService;
  @Mock
  private PushNotificationService pushNotificationService;

  @Test
  @DisplayName("record가 true면 sendToUser로 푸시하며 data{type,reportId}를 담아 보낸다")
  void onProposalReceivedPushesWhenAllowed() {
    // Given
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    given(notificationDispatchService.record(
        eq(userId), eq(NotificationType.RECEIVED_PROPOSAL), anyString(), anyString())).willReturn(true);

    // When
    listener.onProposalReceived(new ReviewProposalReceivedEvent(userId, reportId, adjusterId));

    // Then
    ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pushNotificationService).sendToUser(
        eq(userId), eq(EXPECTED_TITLE), eq(EXPECTED_BODY), dataCaptor.capture());
    assertThat(dataCaptor.getValue())
        .containsEntry("type", "RECEIVED_PROPOSAL")
        .containsEntry("reportId", reportId.toString());
  }

  @Test
  @DisplayName("record가 false면 sendToUser를 호출하지 않는다(인앱만 저장됨)")
  void onProposalReceivedSkipsPushWhenNotAllowed() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(false);

    // When
    listener.onProposalReceived(new ReviewProposalReceivedEvent(userId, UUID.randomUUID(), UUID.randomUUID()));

    // Then
    verify(pushNotificationService, never()).sendToUser(any(), any(), any(), any());
  }

  @Test
  @DisplayName("sendToUser가 예외를 던져도 dispatch가 삼켜 예외가 전파되지 않는다")
  void onProposalReceivedSwallowsPushException() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString())).willReturn(true);
    willThrow(new RuntimeException("FCM down"))
        .given(pushNotificationService).sendToUser(any(), any(), any(), any());

    // When & Then
    assertThatCode(() -> listener.onProposalReceived(
        new ReviewProposalReceivedEvent(userId, UUID.randomUUID(), UUID.randomUUID())))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("record가 예외를 던져도 dispatch가 삼켜 예외가 전파되지 않고 푸시도 없다")
  void onProposalReceivedSwallowsRecordException() {
    // Given
    UUID userId = UUID.randomUUID();
    given(notificationDispatchService.record(any(), any(), anyString(), anyString()))
        .willThrow(new RuntimeException("DB down"));

    // When & Then
    assertThatCode(() -> listener.onProposalReceived(
        new ReviewProposalReceivedEvent(userId, UUID.randomUUID(), UUID.randomUUID())))
        .doesNotThrowAnyException();
    verify(pushNotificationService, never()).sendToUser(any(), any(), any(), any());
  }
}
