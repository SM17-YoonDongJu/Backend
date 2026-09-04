package com.soma.backend.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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

@WebMvcTest(DevAuthController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.dev-login.enabled=true")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("DevAuthController 슬라이스 테스트 (app.dev-login.secret 미설정 = 로컬 기본)")
class DevAuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DevLoginService devLoginService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  @Test
  @DisplayName("POST /auth/dev/login-시크릿 미설정이면 X-Dev-Login-Key 없이도 200 (로컬 기존 동작 회귀 방지)")
  void login_withoutSecretConfigured_returns200() throws Exception {
    given(devLoginService.login(any(), any()))
        .willReturn(new DevLoginResponse(UUID.randomUUID(), "로컬테스트유저", "USER"));

    mockMvc.perform(post("/auth/dev/login")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(jsonPath("$.data.nickname").value("로컬테스트유저"));
  }

  @Test
  @DisplayName("POST /auth/dev/login-시크릿 미설정이면 헤더 값이 무엇이든 무시하고 200")
  void login_withoutSecretConfigured_ignoresHeader() throws Exception {
    given(devLoginService.login(any(), any()))
        .willReturn(new DevLoginResponse(UUID.randomUUID(), "로컬테스트유저", "USER"));

    mockMvc.perform(post("/auth/dev/login")
            .header(DevAuthController.DEV_LOGIN_KEY_HEADER, "아무-값")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.nickname").value("로컬테스트유저"));
  }
}
