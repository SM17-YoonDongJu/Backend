package com.soma.backend.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soma.backend.domain.auth.dto.DevLoginResponse;
import com.soma.backend.domain.auth.service.DevLoginService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

/**
 * 시크릿이 설정된 컨텍스트(= dev 부하테스트 창) 기준 슬라이스 테스트.
 * {@code @TestPropertySource}가 클래스 단위라 "시크릿 미설정" 케이스를 다루는 {@link DevAuthControllerTest}와
 * 한 클래스에 담을 수 없어 분리했다.
 */
@WebMvcTest(DevAuthController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.dev-login.enabled=true",
    "app.dev-login.secret=k6-load-test-secret"
})
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("DevAuthController 슬라이스 테스트 (app.dev-login.secret 설정 = dev 부하테스트 창)")
class DevAuthControllerSecretTest {

  private static final String SECRET = "k6-load-test-secret";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DevLoginService devLoginService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  @Test
  @DisplayName("POST /auth/dev/login-헤더 값이 시크릿과 일치하면 200")
  void login_withMatchingHeader_returns200() throws Exception {
    given(devLoginService.login(any(), any()))
        .willReturn(new DevLoginResponse(UUID.randomUUID(), "k6-adjuster-1", "CERTIFICATED_ADJUSTER"));

    mockMvc.perform(post("/auth/dev/login")
            .header(DevAuthController.DEV_LOGIN_KEY_HEADER, SECRET)
            .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"k6-adjuster-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.nickname").value("k6-adjuster-1"))
        .andExpect(jsonPath("$.data.role").value("CERTIFICATED_ADJUSTER"));
  }

  @Test
  @DisplayName("POST /auth/dev/login-헤더 값이 시크릿과 다르면 403 FORBIDDEN, 서비스는 호출되지 않는다")
  void login_withMismatchedHeader_returns403() throws Exception {
    mockMvc.perform(post("/auth/dev/login")
            .header(DevAuthController.DEV_LOGIN_KEY_HEADER, "wrong-secret")
            .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"k6-adjuster-1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value("403"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(devLoginService, never()).login(any(), any());
  }

  @Test
  @DisplayName("POST /auth/dev/login-헤더가 없으면 불일치와 동일하게 403 FORBIDDEN, 서비스는 호출되지 않는다")
  void login_withoutHeader_returns403() throws Exception {
    mockMvc.perform(post("/auth/dev/login")
            .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"k6-adjuster-1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value("403"))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    verify(devLoginService, never()).login(any(), any());
  }
}
