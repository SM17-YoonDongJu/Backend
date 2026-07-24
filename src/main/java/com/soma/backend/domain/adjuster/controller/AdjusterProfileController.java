package com.soma.backend.domain.adjuster.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.dto.AdjusterMyPageResponse;
import com.soma.backend.domain.adjuster.dto.AdjusterProfileResponse;
import com.soma.backend.domain.adjuster.service.AdjusterProfileQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 손해사정사 마이페이지/프로필 조회. 인증된 사정사(미인증·인증)만 접근한다.
 * {@code /adjusters/me/profile}는 프로필 수정 화면 초기값(flat), {@code /adjusters/me/mypage}는
 * 마이페이지 4개 섹션(프로필·통계·당월 활동·자격)을 내린다.
 */
@RestController
@RequiredArgsConstructor
public class AdjusterProfileController {

  private final AdjusterProfileQueryService adjusterProfileQueryService;

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/adjusters/me/profile")
  public ResponseEntity<ApiResponse<AdjusterProfileResponse>> getProfile(
      @AuthenticationPrincipal CustomUserDetails principal) {
    AdjusterProfileResponse result = adjusterProfileQueryService.getProfile(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  @PreAuthorize("hasAnyRole('CERTIFICATED_ADJUSTER', 'UNCERTIFICATED_ADJUSTER')")
  @GetMapping("/adjusters/me/mypage")
  public ResponseEntity<ApiResponse<AdjusterMyPageResponse>> getMyPage(
      @AuthenticationPrincipal CustomUserDetails principal) {
    AdjusterMyPageResponse result = adjusterProfileQueryService.getMyPage(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }
}
