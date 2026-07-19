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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.notification.dto.NotificationSettingResponse;
import com.soma.backend.domain.notification.dto.NotificationSettingUpdateRequest;
import com.soma.backend.domain.notification.entity.NotificationSetting;
import com.soma.backend.domain.notification.repository.NotificationSettingRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationSettingService 단위 테스트")
class NotificationSettingServiceTest {

  @Mock
  private NotificationSettingRepository notificationSettingRepository;
  @InjectMocks
  private NotificationSettingService service;

  private final UUID userId = UUID.randomUUID();

  @Test
  @DisplayName("설정이 없으면 기본값으로 생성해 반환한다(정산·마케팅만 off)")
  void getMySettings_createsDefault() {
    given(notificationSettingRepository.findById(userId)).willReturn(Optional.empty());
    given(notificationSettingRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

    NotificationSettingResponse response = service.getMySettings(userId);

    assertThat(response.reviewComplete()).isTrue();
    assertThat(response.consultAccepted()).isTrue();
    assertThat(response.reviewDeadlineSoon()).isTrue();
    assertThat(response.settlementNotice()).isFalse();
    assertThat(response.marketing()).isFalse();
    verify(notificationSettingRepository).save(any());
  }

  @Test
  @DisplayName("부분 수정 — 전달한 토글만 바뀌고 나머지는 유지된다")
  void updateMySettings_partial() {
    NotificationSetting existing = NotificationSetting.createDefault(userId);
    given(notificationSettingRepository.findById(userId)).willReturn(Optional.of(existing));

    NotificationSettingUpdateRequest request = new NotificationSettingUpdateRequest(
        null, null, null, null, false, null, null, null, null, true);

    NotificationSettingResponse response = service.updateMySettings(userId, request);

    assertThat(response.reviewComplete()).isFalse();
    assertThat(response.marketing()).isTrue();
    assertThat(response.receivedProposal()).isTrue();
  }
}
