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
@DisplayName("DevAuthController 슬라이스 테스트")
class DevAuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DevLoginService devLoginService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  @Test
  @DisplayName("POST /auth/dev/login-인증 없이도 200과 USER 유저를 반환한다")
  void login_returns200() throws Exception {
    given(devLoginService.login(any(), any()))
        .willReturn(new DevLoginResponse(UUID.randomUUID(), "로컬테스트유저", "USER"));

    mockMvc.perform(post("/auth/dev/login")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(jsonPath("$.data.nickname").value("로컬테스트유저"));
  }
}
