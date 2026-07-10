package com.soma.backend.domain.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.user.dto.UserMeResponse;
import com.soma.backend.domain.user.dto.UserUpdateRequest;
import com.soma.backend.domain.user.service.UserService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 회원 본인 정보 진입점. 인증된 사용자의 조회·수정·탈퇴만 다루며, 대상은 항상 principal 본인이다
 * ({@code /users/me}). SecurityConfig에서 인증 필수 경로이므로 principal은 non-null이다.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  /**
   * 내 정보 조회. 200 + 사용자 정보.
   */
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<UserMeResponse>> getMe(
      @AuthenticationPrincipal CustomUserDetails principal) {

    UserMeResponse result = userService.getMe(principal.getUserId());
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  /**
   * 내 정보 수정(전화번호·지역·프로필사진 부분 수정). 200 + 수정된 정보.
   */
  @PatchMapping("/me")
  public ResponseEntity<ApiResponse<UserMeResponse>> updateMe(
      @AuthenticationPrincipal CustomUserDetails principal,
      @Valid @RequestBody UserUpdateRequest request) {

    UserMeResponse result = userService.updateMe(principal.getUserId(), request);
    return ResponseEntity.ok(ApiResponse.ok("회원 정보가 수정되었습니다.", result));
  }

  /**
   * 회원 탈퇴. Redis RT 삭제 + access blacklist + 쿠키 만료 후 200.
   */
  @DeleteMapping("/me")
  public ResponseEntity<ApiResponse<Void>> withdraw(
      @AuthenticationPrincipal CustomUserDetails principal,
      HttpServletResponse response) {

    userService.withdraw(principal.getUserId(), response);
    return ResponseEntity.ok(ApiResponse.ok("회원 탈퇴가 완료되었습니다.", null));
  }
}
