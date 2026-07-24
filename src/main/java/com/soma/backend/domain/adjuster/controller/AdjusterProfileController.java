package com.soma.backend.domain.adjuster.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterMyProfileResponse;
import com.soma.backend.domain.adjuster.service.AdjusterProfileQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 손해사정사 본인 프로필 조회. 인증된 사정사(미인증·인증)만 접근한다(파트너 헤더·인사말용).
 */
@RestController
@RequiredArgsConstructor
public class AdjusterProfileController {

  private final AdjusterProfileQueryService adjusterProfileQueryService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/adjusters/me/mypage")
  public ResponseEntity<ApiResponse<AdjusterMyProfileResponse>> getMyProfile(
      @AuthenticationPrincipal CustomUserDetails principal) {
    AdjusterMyProfileResponse result = adjusterProfileQueryService.getMyProfile(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
