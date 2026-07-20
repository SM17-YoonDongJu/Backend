package com.soma.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.soma.backend.domain.notification.dto.DeviceTokenResponse;
import com.soma.backend.domain.notification.dto.RegisterDeviceTokenRequest;
import com.soma.backend.domain.notification.entity.DeviceToken;
import com.soma.backend.domain.notification.entity.Platform;
import com.soma.backend.domain.notification.repository.DeviceTokenRepository;

/**
 * DeviceTokenService 단위 테스트. 등록 멱등(upsert)·동시 경쟁 흡수(BC2)·해제 멱등(BC3)을 검증한다.
 * DeviceTokenWriter는 mock으로 대체하고, 경쟁 시 REQUIRES_NEW 격리 자체는 별도 통합 테스트에서 입증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceTokenService 단위 테스트 (멱등 등록/해제)")
class DeviceTokenServiceTest {

  private static final String TOKEN = "fcm-token-abc";

  @InjectMocks
  private DeviceTokenService deviceTokenService;

  @Mock
  private DeviceTokenRepository deviceTokenRepository;

  @Mock
  private DeviceTokenWriter deviceTokenWriter;

  private RegisterDeviceTokenRequest request() {
    return new RegisterDeviceTokenRequest(TOKEN, Platform.ANDROID);
  }

  @Test
  @DisplayName("신규 등록이면 writer.insert로 저장하고 그 결과를 반환한다")
  void register_whenNew_insertsAndReturns() {
    // Given
    UUID userId = UUID.randomUUID();
    given(deviceTokenRepository.findByUserIdAndToken(userId, TOKEN)).willReturn(Optional.empty());
    DeviceTokenResponse saved =
        new DeviceTokenResponse(UUID.randomUUID(), "ANDROID", LocalDateTime.now());
    given(deviceTokenWriter.insert(userId, request())).willReturn(saved);

    // When
    DeviceTokenResponse result = deviceTokenService.register(userId, request());

    // Then
    assertThat(result).isEqualTo(saved);
    then(deviceTokenWriter).should().insert(userId, request());
  }

  @Test
  @DisplayName("이미 등록된 토큰이면 insert 없이 기존 행을 반환한다(멱등, BC1)")
  void register_whenExisting_idempotentNoInsert() {
    // Given
    UUID userId = UUID.randomUUID();
    DeviceToken existing = DeviceToken.create(userId, TOKEN, Platform.ANDROID);
    given(deviceTokenRepository.findByUserIdAndToken(userId, TOKEN))
        .willReturn(Optional.of(existing));

    // When
    DeviceTokenResponse result = deviceTokenService.register(userId, request());

    // Then
    assertThat(result.platform()).isEqualTo("ANDROID");
    then(deviceTokenWriter).should(never()).insert(any(), any());
  }

  @Test
  @DisplayName("동시 등록 경쟁으로 insert가 UNIQUE 위반이면 재조회해 승자 행을 반환한다(멱등 흡수, BC2)")
  void register_whenRaceConflict_absorbsAndReturnsWinner() {
    // Given
    UUID userId = UUID.randomUUID();
    DeviceToken winner = DeviceToken.create(userId, TOKEN, Platform.IOS);
    given(deviceTokenRepository.findByUserIdAndToken(userId, TOKEN))
        .willReturn(Optional.empty())     // register 초기 조회: 없음 → insert 시도
        .willReturn(Optional.of(winner)); // 위반 후 재조회: 경쟁 승자 행
    given(deviceTokenWriter.insert(userId, request()))
        .willThrow(new DataIntegrityViolationException("uq_device_tokens_user_token"));

    // When
    DeviceTokenResponse result = deviceTokenService.register(userId, request());

    // Then
    assertThat(result.platform()).isEqualTo("IOS");
    then(deviceTokenWriter).should().insert(userId, request());
  }

  @Test
  @DisplayName("insert가 무결성 오류인데 재조회도 비면(경쟁 승자 없음) 예외를 그대로 전파한다")
  void register_whenConflictButStillMissing_rethrows() {
    // Given
    UUID userId = UUID.randomUUID();
    given(deviceTokenRepository.findByUserIdAndToken(userId, TOKEN)).willReturn(Optional.empty());
    given(deviceTokenWriter.insert(userId, request()))
        .willThrow(new DataIntegrityViolationException("other constraint"));

    // When & Then
    assertThatThrownBy(() -> deviceTokenService.register(userId, request()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("해제는 본인 소유(user, token) 삭제를 리포지토리에 위임한다(없어도 no-op, BC3)")
  void deregister_delegatesScopedDelete() {
    // Given
    UUID userId = UUID.randomUUID();

    // When
    deviceTokenService.deregister(userId, TOKEN);

    // Then
    then(deviceTokenRepository).should().deleteByUserIdAndToken(userId, TOKEN);
  }
}
