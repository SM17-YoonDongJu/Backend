package com.soma.backend.domain.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.user.dto.UserDashboardResponse;
import com.soma.backend.domain.user.service.UserDashboardQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 고객 홈 BFF 컨트롤러(조회 전용, GET /users/me/dashboard). 로그인 고객 전용 — 인증만 필요하고 role 제한은
 * 없다(SecurityConfig의 {@code anyRequest().authenticated()}가 미인증 401 LOGIN_REQUIRED를 막고, 대상은
 * 항상 principal 본인이다. UserActivitySummaryController 관례). 요청 파라미터는 없다.
 */
@RestController
@RequiredArgsConstructor
public class UserDashboardController {

  private final UserDashboardQueryService userDashboardQueryService;

  @GetMapping("/users/me/dashboard")
  public ResponseEntity<ApiResponse<UserDashboardResponse>> dashboard(
      @AuthenticationPrincipal CustomUserDetails principal) {
    UserDashboardResponse result = userDashboardQueryService.getDashboard(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
