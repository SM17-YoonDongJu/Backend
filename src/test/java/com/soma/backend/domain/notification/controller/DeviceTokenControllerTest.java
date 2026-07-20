package com.soma.backend.domain.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.notification.dto.DeviceTokenResponse;
import com.soma.backend.domain.notification.dto.RegisterDeviceTokenRequest;
import com.soma.backend.domain.notification.service.DeviceTokenService;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * DeviceTokenController API 통합 테스트. 응답 봉투(snake_case)·검증 실패 매핑·인증 경계를 실제 시큐리티
 * 체인에서 검증한다. 서비스는 @MockitoBean으로 대체해 컨트롤러/직렬화/시큐리티 계층만 격리 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("DeviceTokenController API 테스트")
class DeviceTokenControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DeviceTokenService deviceTokenService;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("POST-인증되면 200과 snake_case 봉투(data.id·platform·created_at)를 반환한다")
  void register_authenticated_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID tokenId = UUID.randomUUID();
    given(deviceTokenService.register(eq(userId), any(RegisterDeviceTokenRequest.class)))
        .willReturn(new DeviceTokenResponse(tokenId, "ANDROID", LocalDateTime.of(2026, 7, 20, 10, 0)));
    String body = "{\"token\":\"fcm-token-abc\",\"platform\":\"ANDROID\"}";

    // When & Then
    mockMvc.perform(post("/users/me/device-tokens")
            .with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.message").value("디바이스 토큰을 등록했습니다."))
        .andExpect(jsonPath("$.data.id").value(tokenId.toString()))
        .andExpect(jsonPath("$.data.platform").value("ANDROID"))
        .andExpect(jsonPath("$.data.created_at").exists());
  }

  @Test
  @DisplayName("POST-token이 공백이면 400 BAD_REQUEST(@Valid)")
  void register_blankToken_returns400() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    String body = "{\"token\":\"\",\"platform\":\"ANDROID\"}";

    // When & Then
    mockMvc.perform(post("/users/me/device-tokens")
            .with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("POST-platform이 허용되지 않은 값이면 400 INVALID_REQUEST(enum 역직렬화 실패)")
  void register_invalidPlatform_returns400() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    String body = "{\"token\":\"fcm-token-abc\",\"platform\":\"IPHONE\"}";

    // When & Then
    mockMvc.perform(post("/users/me/device-tokens")
            .with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("POST-미인증이면 401 LOGIN_REQUIRED")
  void register_noAuth_returns401() throws Exception {
    // Given
    String body = "{\"token\":\"fcm-token-abc\",\"platform\":\"ANDROID\"}";

    // When & Then
    mockMvc.perform(post("/users/me/device-tokens")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("DELETE-인증되면 200과 해제 메시지를 반환한다(멱등)")
  void deregister_authenticated_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    String body = "{\"token\":\"fcm-token-abc\"}";

    // When & Then
    mockMvc.perform(delete("/users/me/device-tokens")
            .with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.message").value("디바이스 토큰을 해제했습니다."));
  }

  @Test
  @DisplayName("DELETE-token이 공백이면 400 BAD_REQUEST(@Valid)")
  void deregister_blankToken_returns400() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    String body = "{\"token\":\"\"}";

    // When & Then
    mockMvc.perform(delete("/users/me/device-tokens")
            .with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("DELETE-미인증이면 401 LOGIN_REQUIRED")
  void deregister_noAuth_returns401() throws Exception {
    // Given
    String body = "{\"token\":\"fcm-token-abc\"}";

    // When & Then
    mockMvc.perform(delete("/users/me/device-tokens")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }
}
