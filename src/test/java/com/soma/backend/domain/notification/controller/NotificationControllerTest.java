package com.soma.backend.domain.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.notification.dto.NotificationListResponse;
import com.soma.backend.domain.notification.service.NotificationService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

@WebMvcTest(NotificationController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("NotificationController 슬라이스 테스트")
class NotificationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private NotificationService notificationService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("GET /users/me/notifications-인증되면 200과 목록·미읽음 수(snake_case)를 반환한다")
  void list_authenticated_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    NotificationListResponse.Item item = new NotificationListResponse.Item(
        UUID.randomUUID(), "REVIEW_COMPLETE", "검수 완료", "본문", false, LocalDateTime.now());
    NotificationListResponse response =
        new NotificationListResponse(List.of(item), 3L, 0, 20, 1L, 1);
    given(notificationService.getMyNotifications(eq(userId), any())).willReturn(response);

    // When & Then
    mockMvc.perform(get("/users/me/notifications").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.unread_count").value(3))
        .andExpect(jsonPath("$.data.total_elements").value(1))
        .andExpect(jsonPath("$.data.items[0].type").value("REVIEW_COMPLETE"))
        .andExpect(jsonPath("$.data.items[0].is_read").value(false));
  }

  @Test
  @DisplayName("GET /users/me/notifications-인증이 없으면 401 LOGIN_REQUIRED")
  void list_noAuth_returns401() throws Exception {
    mockMvc.perform(get("/users/me/notifications"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("PATCH /users/me/notifications/read-all-200과 완료 메시지를 반환한다")
  void readAll_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();

    // When & Then
    mockMvc.perform(patch("/users/me/notifications/read-all").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("모든 알림을 읽음 처리했습니다."));
  }

  @Test
  @DisplayName("PATCH /users/me/notifications/{id}/read-200과 완료 메시지를 반환한다")
  void read_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID notificationId = UUID.randomUUID();

    // When & Then
    mockMvc.perform(patch("/users/me/notifications/{id}/read", notificationId)
            .with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("알림을 읽음 처리했습니다."));
  }

  @Test
  @DisplayName("PATCH /users/me/notifications/{id}/read-본인 소유가 아니면 404 NOTIFICATION_NOT_FOUND")
  void read_notOwned_returns404() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID notificationId = UUID.randomUUID();
    willThrow(new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND))
        .given(notificationService).markRead(userId, notificationId);

    // When & Then
    mockMvc.perform(patch("/users/me/notifications/{id}/read", notificationId)
            .with(authenticatedAs(userId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
  }
}
