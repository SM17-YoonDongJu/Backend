package com.soma.backend.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.soma.backend.domain.notification.entity.Platform;

/**
 * 디바이스 토큰 등록 요청(POST /users/me/device-tokens). snake_case → camel 매핑은 Jackson 전역 설정.
 * platform은 enum으로 직접 바인딩하며, 허용되지 않은 값은 역직렬화 실패로 400 INVALID_REQUEST가 된다.
 */
public record RegisterDeviceTokenRequest(
    @NotBlank(message = "토큰은 필수입니다.") @Size(max = 500) String token,
    @NotNull(message = "플랫폼은 필수입니다.") Platform platform) {
}
