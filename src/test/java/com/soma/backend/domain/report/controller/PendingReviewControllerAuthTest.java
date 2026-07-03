package com.soma.backend.domain.report.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.service.PendingReviewQueryService;
import com.soma.backend.domain.report.service.ReportHoldCommandService;
import com.soma.backend.domain.report.service.ReportReviewCommandService;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.ActiveAdjusterArgumentResolver;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * @ActiveAdjuster 인가 가드(401/403) 검증. ArgumentResolver + GlobalExceptionHandler를 실제로 태운다.
 * (DB·SecurityFilterChain 없이 SecurityContext를 직접 세팅하여 401/403/통과만 검증)
 */
class PendingReviewControllerAuthTest {

  private MockMvc mockMvc;
  private PendingReviewQueryService pendingReviewQueryService;

  @BeforeEach
  void setUp() {
    pendingReviewQueryService = Mockito.mock(PendingReviewQueryService.class);
    ReportHoldCommandService reportHoldCommandService = Mockito.mock(ReportHoldCommandService.class);
    ReportReviewCommandService reportReviewCommandService = Mockito.mock(ReportReviewCommandService.class);
    PendingReviewController controller = new PendingReviewController(
        pendingReviewQueryService, reportHoldCommandService, reportReviewCommandService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(new ActiveAdjusterArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateAs(String role) {
    CustomUserDetails principal = new CustomUserDetails(UUID.randomUUID(), role);
    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  @DisplayName("비로그인(principal 없음)이면 401 LOGIN_REQUIRED")
  void unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/reports/pending-review/summary"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("USER 역할이면 403 FORBIDDEN")
  void userRoleReturns403() throws Exception {
    authenticateAs("USER");

    mockMvc.perform(get("/reports/pending-review/summary"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("UNCERTIFICATED_ADJUSTER 역할이면 403 FORBIDDEN")
  void uncertificatedAdjusterReturns403() throws Exception {
    authenticateAs("UNCERTIFICATED_ADJUSTER");

    mockMvc.perform(get("/reports/pending-review/summary"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("ADMIN 역할이면 403 FORBIDDEN")
  void adminReturns403() throws Exception {
    authenticateAs("ADMIN");

    mockMvc.perform(get("/reports/pending-review/summary"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("CERTIFICATED_ADJUSTER 역할이면 200 통과")
  void certificatedAdjusterPasses() throws Exception {
    authenticateAs("CERTIFICATED_ADJUSTER");
    given(pendingReviewQueryService.getSummary()).willReturn(new PendingReviewSummaryResponse(5L, 2L));

    // 표준 ObjectMapper(snake_case 전역설정 미적용) 환경이므로 인가 통과(200)만 검증한다.
    mockMvc.perform(get("/reports/pending-review/summary"))
        .andExpect(status().isOk());
  }
}
