package com.soma.backend.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 디바이스 토큰 해제 요청(DELETE /users/me/device-tokens). 본문(body) 방식이다 —
 * FCM 토큰은 ':'를 포함하고 150자↑라 path/query에 실으면 인코딩·액세스로그 노출 부담이 있다.
 */
public record DeleteDeviceTokenRequest(
    @NotBlank(message = "토큰은 필수입니다.") @Size(max = 500) String token) {
}
