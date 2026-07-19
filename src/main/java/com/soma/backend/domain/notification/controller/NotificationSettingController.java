package com.soma.backend.domain.notification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.NotificationSettingResponse;
import com.soma.backend.domain.notification.dto.NotificationSettingUpdateRequest;
import com.soma.backend.domain.notification.service.NotificationSettingService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 알림 수신 설정 조회·수정. 인증된 사용자 본인(role 무관)만 접근한다({@code /users/me/notification-settings}).
 */
@RestController
@RequestMapping("/users/me/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

  private final NotificationSettingService notificationSettingService;

  /** 내 알림 설정 조회(없으면 기본값 생성 후 반환). 200. */
  @GetMapping
  public ResponseEntity<ApiResponse<NotificationSettingResponse>> getMySettings(
      @AuthenticationPrincipal CustomUserDetails principal) {
    NotificationSettingResponse result = notificationSettingService.getMySettings(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  /** 내 알림 설정 부분 수정(전달한 토글만 변경). 200. */
  @PatchMapping
  public ResponseEntity<ApiResponse<NotificationSettingResponse>> updateMySettings(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestBody NotificationSettingUpdateRequest request) {
    NotificationSettingResponse result =
        notificationSettingService.updateMySettings(principal.getUserId(), request);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
