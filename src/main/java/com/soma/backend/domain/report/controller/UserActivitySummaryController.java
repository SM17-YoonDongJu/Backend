package com.soma.backend.domain.report.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.UserActivitySummaryResponse;
import com.soma.backend.domain.report.service.UserActivitySummaryQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 고객 마이페이지 활동 집계 컨트롤러(조회 전용). 데이터가 report·review 도메인이라 이 컨텍스트에 둔다
 * (AdjusterHomeController 관례). 인증만 필요하고 role 제한은 없다 — SecurityConfig의
 * {@code anyRequest().authenticated()}가 미인증(401)을 막고, 대상은 항상 principal 본인이다.
 */
@RestController
@RequiredArgsConstructor
public class UserActivitySummaryController {

  private final UserActivitySummaryQueryService userActivitySummaryQueryService;

  @GetMapping("/users/me/activity-summary")
  public ResponseEntity<ApiResponse<UserActivitySummaryResponse>> activitySummary(
      @AuthenticationPrincipal CustomUserDetails principal) {
    UserActivitySummaryResponse result =
        userActivitySummaryQueryService.getActivitySummary(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
