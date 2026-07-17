package com.soma.backend.domain.notification.controller;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.notification.dto.NotificationListResponse;
import com.soma.backend.domain.notification.service.NotificationService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 인앱 알림함 진입점. 인증된 사용자(role 무관) 본인의 알림만 조회·읽음 처리한다({@code /users/me/notifications}).
 * SecurityConfig에서 {@code /users/**}는 인증 필수 경로이므로 principal은 non-null이다.
 */
@RestController
@RequestMapping("/users/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;

  /** 내 알림 목록(최신순, 페이지네이션) + 미읽음 수. 200. */
  @GetMapping
  public ResponseEntity<ApiResponse<NotificationListResponse>> getMyNotifications(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {

    Pageable pageable = PageRequest.of(page, size);
    NotificationListResponse result = notificationService.getMyNotifications(principal.getUserId(), pageable);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  /** 내 알림을 모두 읽음 처리. 200. */
  @PatchMapping("/read-all")
  public ResponseEntity<ApiResponse<Void>> readAll(
      @AuthenticationPrincipal CustomUserDetails principal) {

    notificationService.markAllRead(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok("모든 알림을 읽음 처리했습니다.", null));
  }

  /** 내 알림 1건 읽음 처리. 본인 소유가 아니면 404. 200. */
  @PatchMapping("/{notificationId}/read")
  public ResponseEntity<ApiResponse<Void>> read(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID notificationId) {

    notificationService.markRead(principal.getUserId(), notificationId);
    return ResponseEntity.ok(ApiResponse.ok("알림을 읽음 처리했습니다.", null));
  }
}
