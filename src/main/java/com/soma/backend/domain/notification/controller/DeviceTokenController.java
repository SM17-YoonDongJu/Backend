package com.soma.backend.domain.notification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.DeleteDeviceTokenRequest;
import com.soma.backend.domain.notification.dto.DeviceTokenResponse;
import com.soma.backend.domain.notification.dto.RegisterDeviceTokenRequest;
import com.soma.backend.domain.notification.service.DeviceTokenService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 디바이스 토큰 등록·해제 진입점({@code /users/me/device-tokens}). 인증된 사용자(role 무관) 본인 토큰만 조작한다.
 * SecurityConfig에서 {@code /users/**}는 인증 필수 경로이므로 principal은 non-null이다. 등록·해제 모두 멱등 200이다.
 */
@RestController
@RequestMapping("/users/me/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

  private final DeviceTokenService deviceTokenService;

  /** 디바이스 토큰 등록(멱등 upsert). 이미 등록된 토큰이면 기존 행을 그대로 반환한다. 200. */
  @PostMapping
  public ResponseEntity<ApiResponse<DeviceTokenResponse>> register(
      @AuthenticationPrincipal CustomUserDetails principal,
      @Valid @RequestBody RegisterDeviceTokenRequest request) {

    DeviceTokenResponse response = deviceTokenService.register(principal.getUserId(), request);
    return ResponseEntity.ok(ApiResponse.ok("디바이스 토큰을 등록했습니다.", response));
  }

  /** 디바이스 토큰 해제(멱등). 본인 소유 토큰만 삭제하고, 없어도 no-op 200이다. */
  @DeleteMapping
  public ResponseEntity<ApiResponse<Void>> deregister(
      @AuthenticationPrincipal CustomUserDetails principal,
      @Valid @RequestBody DeleteDeviceTokenRequest request) {

    deviceTokenService.deregister(principal.getUserId(), request.token());
    return ResponseEntity.ok(ApiResponse.ok("디바이스 토큰을 해제했습니다.", null));
  }
}
