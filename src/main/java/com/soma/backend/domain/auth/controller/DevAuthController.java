package com.soma.backend.domain.auth.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.auth.dto.DevLoginRequest;
import com.soma.backend.domain.auth.dto.DevLoginResponse;
import com.soma.backend.domain.auth.service.DevLoginService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.response.ApiResponse;

/**
 * 로컬 개발 전용 로그인 엔드포인트. 소셜 OAuth 없이 테스트 유저의 access_token 쿠키를 발급한다.
 * {@code app.dev-login.enabled=true}일 때만 빈으로 등록되므로, 이 값을 켜지 않은 배포 아티팩트에서는
 * 경로 자체가 존재하지 않는다({@code /auth/**}는 이미 permitAll). prod 프로파일 차단은 {@code DevLoginGuard} 담당.
 *
 * <p>부하테스트(k6) 때문에 dev 배포에서도 한시적으로 열 수 있어, {@code app.dev-login.secret}이 설정된
 * 경우에만 {@code X-Dev-Login-Key} 헤더 검증을 추가로 건다. 값이 없으면(로컬 기본) 검증을 완전히 건너뛴다.
 * 단 {@code dev} 프로파일에서는 시크릿 없이 여는 것 자체를 기동 실패로 막는다(fail-closed) —
 * {@code DevLoginGuard}가 prod에서 하는 것과 대칭되는 마지막 방어선이다.
 */
@Slf4j
@RestController
@RequestMapping("/auth/dev")
@ConditionalOnProperty(prefix = "app.dev-login", name = "enabled", havingValue = "true")
public class DevAuthController {

  /** 공유 시크릿을 싣는 요청 헤더. {@code app.dev-login.secret}이 설정된 경우에만 필수다. */
  public static final String DEV_LOGIN_KEY_HEADER = "X-Dev-Login-Key";

  private static final String DEV_PROFILE = "dev";

  private final DevLoginService devLoginService;
  private final String secret;

  public DevAuthController(
      DevLoginService devLoginService,
      Environment environment,
      @Value("${app.dev-login.secret:}") String secret) {
    this.devLoginService = devLoginService;
    this.secret = secret;
    assertSecretConfiguredOnDev(environment);
  }

  /**
   * 테스트 유저로 로그인. access_token 쿠키를 발급하고 유저 정보를 반환한다. 200.
   * 시크릿이 설정된 환경에서 헤더가 없거나 값이 다르면 403.
   */
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<DevLoginResponse>> login(
      @Parameter(description = "dev 로그인 공유 시크릿. 서버에 app.dev-login.secret이 설정된 경우에만 필수이며, "
          + "누락·불일치 시 403을 반환한다. 설정되지 않은 환경(로컬)에서는 무시된다.")
      @RequestHeader(name = DEV_LOGIN_KEY_HEADER, required = false) @Nullable String devLoginKey,
      @RequestBody(required = false) @Nullable DevLoginRequest request,
      HttpServletResponse response) {
    verifySecret(devLoginKey);
    DevLoginResponse data = devLoginService.login(response, request);
    return ResponseEntity.ok(ApiResponse.ok("로컬 개발용 로그인이 완료되었습니다.", data));
  }

  /**
   * 시크릿이 설정된 경우에만 헤더를 상수 시간으로 대조한다. 누락과 불일치를 같은 응답(FORBIDDEN)으로 처리해
   * "키가 틀렸다"는 오라클을 주지 않는다.
   */
  private void verifySecret(@Nullable String provided) {
    if (!isSecretConfigured()) {
      return;
    }
    if (provided == null
        || !MessageDigest.isEqual(
            provided.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private boolean isSecretConfigured() {
    return secret != null && !secret.isBlank();
  }

  /**
   * dev 프로파일에서 시크릿 없이 백도어가 열려 있으면 기동 자체를 막는다(fail-closed). dev는 CORS로
   * Vercel 프론트 오리진을 허용하는 등 외부에서 닿을 수 있는 서버라, 설정 실수(secret 누락) 하나가 곧바로
   * 인증 우회로 이어진다 — 경고 로그만으로는 이 리스크를 막지 못한다. {@code DevLoginGuard}가 prod에서
   * enabled=true 자체를 막는 것과 대칭이다.
   */
  private void assertSecretConfiguredOnDev(Environment environment) {
    if (isSecretConfigured()) {
      return;
    }
    for (String profile : environment.getActiveProfiles()) {
      if (DEV_PROFILE.equalsIgnoreCase(profile)) {
        throw new IllegalStateException(
            "app.dev-login.enabled=true인 dev 프로파일에서는 app.dev-login.secret이 필수입니다 "
                + "(시크릿 없이 dev 로그인 백도어가 열리는 것을 방지).");
      }
    }
  }
}
