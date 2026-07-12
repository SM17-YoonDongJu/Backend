package com.soma.backend.domain.auth.controller;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.OAuthCallbackResponse;
import com.soma.backend.domain.auth.dto.RegisterRequest;
import com.soma.backend.domain.auth.dto.RegisterResponse;
import com.soma.backend.domain.auth.service.AuthLogoutService;
import com.soma.backend.domain.auth.service.AuthRegisterService;
import com.soma.backend.domain.auth.service.AuthReissueService;
import com.soma.backend.domain.auth.service.OAuthLoginService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 인증 진입점. OAuth 콜백·회원가입·로그아웃·재발급을 담당한다. SecurityConfig에서 {@code /auth/**}는
 * permitAll이며, 토큰은 응답 바디가 아니라 HttpOnly 쿠키로만 전달된다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private final OAuthLoginService oAuthLoginService;
  private final AuthRegisterService authRegisterService;
  private final AuthReissueService authReissueService;
  private final AuthLogoutService authLogoutService;

  /**
   * 프론트가 받은 인가코드로 로그인/가입 분기. 기존 회원은 쿠키 발급, 신규 회원은 가입 티켓 반환.
   */
  @GetMapping("/oauth2/{provider}/callback")
  public ResponseEntity<ApiResponse<OAuthCallbackResponse>> oauthCallback(
      @PathVariable String provider,
      @RequestParam String code,
      @RequestParam(required = false) @Nullable String state,
      HttpServletResponse response) {

    OAuthCallbackResponse result = oAuthLoginService.handleCallback(response, provider, code, state);
    return ResponseEntity.ok(ApiResponse.ok(result));
  }

  /**
   * 가입 티켓 기반 회원가입. 성공 시 201 + 쿠키 발급.
   */
  @PostMapping("/register")
  public ResponseEntity<ApiResponse<RegisterResponse>> register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletResponse response) {

    RegisterResponse result = authRegisterService.register(response, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.created("회원가입이 완료되었습니다.", result));
  }

  /**
   * 로그아웃. Redis RT 삭제 + 쿠키 만료. 인증이 없어도 멱등하게 200.
   */
  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(
      @AuthenticationPrincipal @Nullable CustomUserDetails principal,
      HttpServletRequest request,
      HttpServletResponse response) {

    UUID userId = principal != null ? principal.getUserId() : null;
    authLogoutService.logout(request, response, userId);
    return ResponseEntity.ok(ApiResponse.ok());
  }

  /**
   * 토큰 재발급(RTR). refresh 쿠키를 검증하고 새 토큰 쌍을 쿠키로 발급. 200.
   */
  @PostMapping("/reissue")
  public ResponseEntity<ApiResponse<Void>> reissue(
      HttpServletRequest request,
      HttpServletResponse response) {

    authReissueService.reissue(request, response);
    return ResponseEntity.ok(ApiResponse.ok());
  }
}
