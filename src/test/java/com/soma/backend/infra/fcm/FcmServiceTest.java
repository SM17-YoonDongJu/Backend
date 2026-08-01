package com.soma.backend.infra.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.ObjectProvider;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;

/**
 * FcmService(순수 발송 어댑터) 단위 테스트. 정적 팩터리 FirebaseMessaging.getInstance(...)는 mockStatic으로,
 * final 예외 FirebaseMessagingException은 inline mock으로 대체한다(프로젝트 기본 inline mock maker). DB 무관.
 */
@DisplayName("FcmService 단위 테스트 (순수 발송 어댑터)")
class FcmServiceTest {

  private ObjectProvider<FirebaseApp> firebaseAppProvider;
  private FirebaseApp firebaseApp;
  private FirebaseMessaging messaging;
  private MockedStatic<FirebaseMessaging> firebaseMessagingStatic;
  private FcmService fcmService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    firebaseAppProvider = mock(ObjectProvider.class);
    firebaseApp = mock(FirebaseApp.class);
    messaging = mock(FirebaseMessaging.class);
    firebaseMessagingStatic = mockStatic(FirebaseMessaging.class);
    fcmService = new FcmService(firebaseAppProvider);
  }

  @AfterEach
  void tearDown() {
    firebaseMessagingStatic.close();
  }

  @Test
  @DisplayName("FirebaseApp이 없으면(stub) 빈 리스트를 반환하고 getInstance(발송)를 하지 않는다")
  void send_whenNoFirebaseApp_returnsEmptyAndNoSideEffect() {
    // Given
    given(firebaseAppProvider.getIfAvailable()).willReturn(null);

    // When
    List<String> dead = fcmService.send(List.of("token-1"), "제목", "본문", Map.of());

    // Then
    assertThat(dead).isEmpty();
    firebaseMessagingStatic.verify(() -> FirebaseMessaging.getInstance(any(FirebaseApp.class)), never());
  }

  @Test
  @DisplayName("토큰 목록이 비면 발송 없이 빈 리스트를 반환한다")
  void send_whenNoTokens_returnsEmpty() {
    // Given
    given(firebaseAppProvider.getIfAvailable()).willReturn(firebaseApp);

    // When
    List<String> dead = fcmService.send(List.of(), "제목", "본문", Map.of());

    // Then
    assertThat(dead).isEmpty();
    firebaseMessagingStatic.verify(() -> FirebaseMessaging.getInstance(any(FirebaseApp.class)), never());
  }

  @Test
  @DisplayName("정상 발송되면 죽은 토큰이 없다")
  void send_whenSuccess_returnsNoDeadTokens() throws Exception {
    // Given
    stubMessagingAvailable();
    given(messaging.send(any(Message.class))).willReturn("message-id");

    // When
    List<String> dead = fcmService.send(List.of("token-1"), "제목", "본문", Map.of());

    // Then
    assertThat(dead).isEmpty();
    verify(messaging).send(any(Message.class));
  }

  @Test
  @DisplayName("UNREGISTERED 오류면 해당 토큰을 죽은 토큰으로 반환한다")
  void send_whenUnregistered_marksTokenDead() throws Exception {
    // Given — 예외는 미리 완성해 둔다(별도 문장). 진행 중인 given(...) 안에서 stubbing을 겹치면
    // UnfinishedStubbingException이 난다.
    stubMessagingAvailable();
    FirebaseMessagingException error = messagingException(MessagingErrorCode.UNREGISTERED);
    given(messaging.send(any(Message.class))).willThrow(error);

    // When
    List<String> dead = fcmService.send(List.of("token-1"), "제목", "본문", Map.of());

    // Then
    assertThat(dead).containsExactly("token-1");
  }

  @Test
  @DisplayName("INVALID_ARGUMENT 오류면 해당 토큰을 죽은 토큰으로 반환한다")
  void send_whenInvalidArgument_marksTokenDead() throws Exception {
    // Given
    stubMessagingAvailable();
    FirebaseMessagingException error = messagingException(MessagingErrorCode.INVALID_ARGUMENT);
    given(messaging.send(any(Message.class))).willThrow(error);

    // When
    List<String> dead = fcmService.send(List.of("token-1"), "제목", "본문", Map.of());

    // Then
    assertThat(dead).containsExactly("token-1");
  }

  @Test
  @DisplayName("UNAVAILABLE 같은 일시 오류면 토큰을 유지한다(죽은 토큰 아님)")
  void send_whenTransientError_keepsToken() throws Exception {
    // Given
    stubMessagingAvailable();
    FirebaseMessagingException error = messagingException(MessagingErrorCode.UNAVAILABLE);
    given(messaging.send(any(Message.class))).willThrow(error);

    // When
    List<String> dead = fcmService.send(List.of("token-1"), "제목", "본문", Map.of());

    // Then
    assertThat(dead).isEmpty();
  }

  @Test
  @DisplayName("다건 부분 실패 시 실패 토큰만 죽은 토큰이 되고 나머지는 계속 발송된다")
  void send_whenPartialFailure_isolatesPerToken() throws Exception {
    // Given
    stubMessagingAvailable();
    FirebaseMessagingException unregisteredError = messagingException(MessagingErrorCode.UNREGISTERED);
    given(messaging.send(any(Message.class)))
        .willReturn("id-1")
        .willThrow(unregisteredError)
        .willReturn("id-3");

    // When
    List<String> dead =
        fcmService.send(List.of("token-1", "token-2", "token-3"), "제목", "본문", Map.of());

    // Then
    assertThat(dead).containsExactly("token-2");
    verify(messaging, times(3)).send(any(Message.class));
  }

  @Test
  @DisplayName("data가 null이어도 예외 없이 발송한다")
  void send_whenNullData_sendsWithoutError() throws Exception {
    // Given
    stubMessagingAvailable();
    given(messaging.send(any(Message.class))).willReturn("message-id");

    // When
    List<String> dead = fcmService.send(List.of("token-1"), "제목", "본문", null);

    // Then
    assertThat(dead).isEmpty();
    verify(messaging).send(any(Message.class));
  }

  private void stubMessagingAvailable() {
    given(firebaseAppProvider.getIfAvailable()).willReturn(firebaseApp);
    firebaseMessagingStatic.when(() -> FirebaseMessaging.getInstance(any(FirebaseApp.class)))
        .thenReturn(messaging);
  }

  private FirebaseMessagingException messagingException(MessagingErrorCode code) {
    FirebaseMessagingException ex = mock(FirebaseMessagingException.class);
    when(ex.getMessagingErrorCode()).thenReturn(code);
    return ex;
  }
}
