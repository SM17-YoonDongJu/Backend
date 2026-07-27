package com.soma.backend.domain.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.user.dto.UserInsuranceListResponse;
import com.soma.backend.domain.user.service.UserInsuranceQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 내 보험 정보 조회 컨트롤러(조회 전용, GET /users/me/insurances). 로그인 사용자 전용 — 인증만 필요하고
 * role 제한은 없다(SecurityConfig의 {@code anyRequest().authenticated()}가 미인증 401 LOGIN_REQUIRED를
 * 막고, 대상은 항상 principal 본인이다. UserDashboardController 관례). 요청 파라미터는 없다.
 */
@RestController
@RequiredArgsConstructor
public class UserInsuranceController {

  private final UserInsuranceQueryService userInsuranceQueryService;

  @GetMapping("/users/me/insurances")
  public ResponseEntity<ApiResponse<UserInsuranceListResponse>> getMyInsurances(
      @AuthenticationPrincipal CustomUserDetails principal) {
    UserInsuranceListResponse result = userInsuranceQueryService.getMyInsurances(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
