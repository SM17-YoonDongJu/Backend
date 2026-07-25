package com.soma.backend.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.user.dto.UserMeResponse;
import com.soma.backend.domain.user.service.UserService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

@WebMvcTest(UserController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("UserController 슬라이스 테스트")
class UserControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserService userService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private UserMeResponse sampleResponse(UUID userId) {
    return new UserMeResponse(
        userId, "홍길동", "010-1234-5678", "USER", null, "서울", null, "kakao", LocalDateTime.now());
  }

  @Test
  @DisplayName("GET /users/me-인증된 사용자면 200과 내 정보를 반환한다")
  void getMe_authenticated_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    given(userService.getMe(userId)).willReturn(sampleResponse(userId));

    // When & Then
    mockMvc.perform(get("/users/me").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.user_id").value(userId.toString()))
        .andExpect(jsonPath("$.data.phone_number").value("010-1234-5678"))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(jsonPath("$.data.social_provider").value("kakao"));
  }

  @Test
  @DisplayName("GET /users/me-인증이 없으면 401 LOGIN_REQUIRED")
  void getMe_noAuth_returns401() throws Exception {
    mockMvc.perform(get("/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("PATCH /users/me-정상 수정이면 200과 수정 메시지를 반환한다")
  void updateMe_valid_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    given(userService.updateMe(any(), any())).willReturn(sampleResponse(userId));
    String body = "{\"region\":[\"서울\"]}";

    // When & Then
    mockMvc.perform(patch("/users/me").with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("회원 정보가 수정되었습니다."))
        .andExpect(jsonPath("$.data.region").value("서울"));
  }

  @Test
  @DisplayName("PATCH /users/me-전화번호 형식이 틀리면 400 BAD_REQUEST")
  void updateMe_invalidPhone_returns400() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    String body = "{\"phone_number\":\"010-abc\"}";

    // When & Then
    mockMvc.perform(patch("/users/me").with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("DELETE /users/me-탈퇴하면 200과 탈퇴 완료 메시지를 반환한다")
  void withdraw_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();

    // When & Then
    mockMvc.perform(delete("/users/me").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."))
        .andExpect(jsonPath("$.data").doesNotExist());
  }
}
