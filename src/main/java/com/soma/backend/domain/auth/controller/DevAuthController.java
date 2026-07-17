package com.soma.backend.domain.auth.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.DevLoginRequest;
import com.soma.backend.domain.auth.dto.DevLoginResponse;
import com.soma.backend.domain.auth.service.DevLoginService;
import com.soma.backend.global.response.ApiResponse;

/**
 * 로컬 개발 전용 로그인 엔드포인트. 소셜 OAuth 없이 테스트 유저의 access_token 쿠키를 발급한다.
 * {@code app.dev-login.enabled=true}(로컬 프로파일에서만 활성)일 때만 빈으로 등록되므로, 배포
 * 아티팩트(dev·prod)에서는 경로 자체가 존재하지 않는다({@code /auth/**}는 이미 permitAll).
 */
@RestController
@RequestMapping("/auth/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dev-login", name = "enabled", havingValue = "true")
public class DevAuthController {

  private final DevLoginService devLoginService;

  /**
   * 테스트 유저로 로그인. access_token 쿠키를 발급하고 유저 정보를 반환한다. 200.
   */
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<DevLoginResponse>> login(
      @RequestBody(required = false) @Nullable DevLoginRequest request,
      HttpServletResponse response) {
    DevLoginResponse data = devLoginService.login(response, request);
    return ResponseEntity.ok(ApiResponse.ok("로컬 개발용 로그인이 완료되었습니다.", data));
  }
}
