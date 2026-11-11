package com.soma.backend.domain.auth.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.PasswordAuthResponse;
import com.soma.backend.domain.auth.dto.PasswordLoginRequest;
import com.soma.backend.domain.auth.dto.PasswordSignupRequest;
import com.soma.backend.domain.auth.service.PasswordLoginService;
import com.soma.backend.domain.auth.service.PasswordSignupService;
import com.soma.backend.global.response.ApiResponse;

/**
 * 아이디·비밀번호 일반 로그인 진입점(이슈 #389). 소셜 로그인과 나란히 쓰는 정식 로그인 경로다.
 *
 * <p>{@code /auth/**}는 SecurityConfig에서 permitAll이고 {@code JwtFilter.shouldNotFilter()}가 access
 * 토큰 검증을 건너뛰므로, 이 경로를 그 아래 두는 것만으로 시큐리티 설정 변경 없이 미인증 접근이 된다.
 * 토큰은 응답 바디가 아니라 HttpOnly 쿠키로만 발급된다.
 *
 * <p>{@code app.password-login.enabled=false}면 컨트롤러·서비스 빈이 모두 등록되지 않아 경로 자체가
 * 사라진다(기본값은 등록 — 시연·운영에서 쓰는 정식 경로라 {@code DevAuthController} 같은 프로파일
 * 하드코딩·시크릿 헤더는 두지 않는다).
 */
@RestController
@RequestMapping("/auth/password")
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.password-login", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PasswordAuthController {

  private final PasswordSignupService passwordSignupService;
  private final PasswordLoginService passwordLoginService;

  /**
   * 아이디·비밀번호 회원가입. 성공 시 201 + 쿠키 발급. 아이디가 이미 있으면 409.
   */
  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<PasswordAuthResponse> signup(
      @Valid @RequestBody PasswordSignupRequest request,
      HttpServletResponse response) {

    PasswordAuthResponse result = passwordSignupService.signup(response, request);
    return ApiResponse.created("회원가입이 완료되었습니다.", result);
  }

  /**
   * 아이디·비밀번호 로그인. 성공 시 200 + 쿠키 발급. 자격증명이 맞지 않으면 사유를 구분하지 않고 401.
   */
  @PostMapping("/login")
  public ApiResponse<PasswordAuthResponse> login(
      @Valid @RequestBody PasswordLoginRequest request,
      HttpServletResponse response) {

    PasswordAuthResponse result = passwordLoginService.login(response, request);
    return ApiResponse.ok("로그인이 완료되었습니다.", result);
  }
}
