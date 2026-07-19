package com.soma.backend.domain.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.NotificationSettingResponse;
import com.soma.backend.domain.notification.dto.NotificationSettingUpdateRequest;
import com.soma.backend.domain.notification.entity.NotificationSetting;
import com.soma.backend.domain.notification.repository.NotificationSettingRepository;

/**
 * 알림 설정 유스케이스. 설정 행이 없으면 기본값으로 생성해 반환한다(가입 시 선생성 없이 최초 접근 시 lazy 생성).
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

  private final NotificationSettingRepository notificationSettingRepository;

  @Transactional
  public NotificationSettingResponse getMySettings(UUID userId) {
    return NotificationSettingResponse.from(getOrCreate(userId));
  }

  @Transactional
  public NotificationSettingResponse updateMySettings(UUID userId, NotificationSettingUpdateRequest request) {
    NotificationSetting setting = getOrCreate(userId);
    setting.applyPatch(
        request.newReviewRequest(), request.consultMessage(), request.settlementNotice(),
        request.reviewDeadlineSoon(), request.reviewComplete(), request.receivedProposal(),
        request.consultAccepted(), request.analysisComplete(), request.identityVerified(),
        request.marketing());
    return NotificationSettingResponse.from(setting);
  }

  private NotificationSetting getOrCreate(UUID userId) {
    return notificationSettingRepository.findById(userId)
        .orElseGet(() -> notificationSettingRepository.save(NotificationSetting.createDefault(userId)));
  }
}
