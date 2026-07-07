package com.soma.backend.domain.report.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.service.PendingReviewQueryService;
import com.soma.backend.domain.report.service.ReportHoldCommandService;
import com.soma.backend.domain.report.service.ReportReviewCommandService;
import com.soma.backend.global.config.WebMvcConfig;
import com.soma.backend.global.security.ActiveAdjusterArgumentResolver;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;

/**
 * 검수 대기 API 인가 가드 검증. 실제 SecurityFilterChain(anyRequest.authenticated)과
 * 컨트롤러 클래스 레벨 {@code @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")}를 태워
 * 비로그인 401 / 비사정사 403 / 사정사 통과를 확인한다.
 */
@WebMvcTest(PendingReviewController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    ActiveAdjusterArgumentResolver.class,
    WebMvcConfig.class
})
@DisplayName("PendingReviewController 인가 가드 테스트")
class PendingReviewControllerAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PendingReviewQueryService pendingReviewQueryService;

  @MockitoBean
  private ReportHoldCommandService reportHoldCommandService;

  @MockitoBean
  private ReportReviewCommandService reportReviewCommandService;

  private Authentication authenticationAs(String role) {
    CustomUserDetails principal = new CustomUserDetails(UUID.randomUUID(), role);
    return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
  }

  @Test
  @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
  void unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/reports/pending-review/summary"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("USER 역할이면 403 FORBIDDEN")
  void userRole_returns403() throws Exception {
    mockMvc.perform(get("/reports/pending-review/summary")
            .with(authentication(authenticationAs("USER"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("UNCERTIFICATED_ADJUSTER 역할이면 403 FORBIDDEN")
  void uncertificatedAdjuster_returns403() throws Exception {
    mockMvc.perform(get("/reports/pending-review/summary")
            .with(authentication(authenticationAs("UNCERTIFICATED_ADJUSTER"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("CERTIFICATED_ADJUSTER 역할이면 200 통과")
  void certificatedAdjuster_passes() throws Exception {
    given(pendingReviewQueryService.getSummary())
        .willReturn(new PendingReviewSummaryResponse(5L, 2L));

    mockMvc.perform(get("/reports/pending-review/summary")
            .with(authentication(authenticationAs("CERTIFICATED_ADJUSTER"))))
        .andExpect(status().isOk());
  }
}
