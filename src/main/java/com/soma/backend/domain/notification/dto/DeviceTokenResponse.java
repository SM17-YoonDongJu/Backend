package com.soma.backend.domain.notification.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.notification.entity.DeviceToken;

/**
 * 디바이스 토큰 등록 응답. 원본 토큰(자격증명 성격)은 되돌려주지 않고 id·platform·created_at만 노출한다.
 * 필드명은 Jackson 전역 설정으로 snake_case 직렬화된다(createdAt → created_at).
 */
public record DeviceTokenResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String platform,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {

  public static DeviceTokenResponse from(DeviceToken deviceToken) {
    return new DeviceTokenResponse(
        deviceToken.getId(),
        deviceToken.getPlatform().name(),
        deviceToken.getCreatedAt());
  }
}
