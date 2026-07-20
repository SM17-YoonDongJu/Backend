package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.entity.Platform;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;
import com.soma.backend.infra.fcm.FcmService;

/**
 * PushNotificationService(발송 오케스트레이터) 단위 테스트. 죽은 토큰 정리 책임이 infra(FcmService)가 아니라
 * 소유 도메인(DeadDeviceTokenCleaner)에 있고, 발송 I/O가 트랜잭션 밖에서 이뤄짐을 검증한다(strict layering).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushNotificationService 단위 테스트 (발송 오케스트레이터)")
class PushNotificationServiceTest {

  @InjectMocks
  private PushNotificationService pushNotificationService;

  @Mock
  private DeviceTokenRepository deviceTokenRepository;

  @Mock
  private FcmService fcmService;

  @Mock
  private DeadDeviceTokenCleaner deadDeviceTokenCleaner;

  @Test
  @DisplayName("죽은 토큰이 있으면 추출한 토큰으로 발송하고 DeadDeviceTokenCleaner로 정리한다")
  void sendToUser_whenDeadTokens_deletesThem() {
    // Given
    UUID userId = UUID.randomUUID();
    DeviceToken t1 = DeviceToken.create(userId, "token-1", Platform.ANDROID);
    DeviceToken t2 = DeviceToken.create(userId, "token-2", Platform.IOS);
    Map<String, String> data = Map.of("reportId", "r-1");
    given(deviceTokenRepository.findByUserId(userId)).willReturn(List.of(t1, t2));
    given(fcmService.send(anyList(), eq("제목"), eq("본문"), eq(data))).willReturn(List.of("token-2"));

    // When
    pushNotificationService.sendToUser(userId, "제목", "본문", data);

    // Then
    ArgumentCaptor<List<String>> tokensCaptor = ArgumentCaptor.forClass(List.class);
    then(fcmService).should().send(tokensCaptor.capture(), eq("제목"), eq("본문"), eq(data));
    assertThat(tokensCaptor.getValue()).containsExactly("token-1", "token-2");
    then(deadDeviceTokenCleaner).should().delete(userId, List.of("token-2"));
  }

  @Test
  @DisplayName("죽은 토큰이 없으면 삭제를 호출하지 않는다")
  void sendToUser_whenNoDeadTokens_noDelete() {
    // Given
    UUID userId = UUID.randomUUID();
    DeviceToken t1 = DeviceToken.create(userId, "token-1", Platform.ANDROID);
    given(deviceTokenRepository.findByUserId(userId)).willReturn(List.of(t1));
    given(fcmService.send(anyList(), any(), any(), any())).willReturn(List.of());

    // When
    pushNotificationService.sendToUser(userId, "제목", "본문", Map.of());

    // Then
    then(deadDeviceTokenCleaner).should(never()).delete(any(), any());
  }

  @Test
  @DisplayName("등록된 토큰이 없으면 발송도 삭제도 하지 않는다")
  void sendToUser_whenNoTokens_noSendNoDelete() {
    // Given
    UUID userId = UUID.randomUUID();
    given(deviceTokenRepository.findByUserId(userId)).willReturn(List.of());

    // When
    pushNotificationService.sendToUser(userId, "제목", "본문", Map.of());

    // Then
    then(fcmService).shouldHaveNoInteractions();
    then(deadDeviceTokenCleaner).should(never()).delete(any(), any());
  }
}
