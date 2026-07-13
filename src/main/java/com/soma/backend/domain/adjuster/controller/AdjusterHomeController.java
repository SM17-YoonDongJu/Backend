package com.soma.backend.domain.adjuster.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterHomeResponse;
import com.soma.backend.domain.adjuster.service.AdjusterHomeQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 사정사 홈 대시보드 집계 컨트롤러(조회 전용).
 *
 * <p>미인증(401)·role 불일치(403)는 {@code @PreAuthorize}(메서드 시큐리티)가 판정하고 예외는
 * {@code GlobalExceptionHandler}가 매핑한다. 조회이므로 CERTIFICATED_ADJUSTER·UNCERTIFICATED_ADJUSTER
 * 모두 허용한다. 요청 사정사 userId는 {@code @AuthenticationPrincipal}로 주입한다.
 */
@RestController
@RequiredArgsConstructor
public class AdjusterHomeController {

  private final AdjusterHomeQueryService adjusterHomeQueryService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/adjusters/me/home")
  public ResponseEntity<ApiResponse<AdjusterHomeResponse>> home(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam(name = "in_progress_limit", defaultValue = "5") int inProgressLimit) {
    AdjusterHomeResponse result = adjusterHomeQueryService.getHome(principal.getUserId(), inProgressLimit);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
