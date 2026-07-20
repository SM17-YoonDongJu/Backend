package com.soma.backend.domain.report.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.soma.backend.domain.report.dto.Pagination;
import com.soma.backend.domain.report.dto.ProposalListResponse;
import com.soma.backend.domain.report.service.ProposalQueryService;
import com.soma.backend.domain.report.service.ReportCommandService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

/**
 * ReportController 슬라이스 테스트. 인증(401)·로그인 통과(200)를 실제 SecurityFilterChain으로 검증한다
 * (@AuthenticationPrincipal + SecurityConfig anyRequest().authenticated()). 미인증 401은 필터가 처리한다.
 */
@WebMvcTest(ReportController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("ReportController 슬라이스 테스트")
class ReportControllerAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ReportCommandService reportCommandService;

  @MockitoBean
  private ProposalQueryService proposalQueryService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("비로그인이면 GET /reports/{id}/proposals는 401 LOGIN_REQUIRED")
  void unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/reports/{id}/proposals", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("로그인 사용자는 GET /reports/{id}/proposals 200")
  void authenticatedProposalsReturns200() throws Exception {
    given(proposalQueryService.getProposals(any(), any(), anyInt(), anyInt()))
        .willReturn(new ProposalListResponse(List.of(), new Pagination(1, 10, 0, 0, false)));

    mockMvc.perform(get("/reports/{id}/proposals", UUID.randomUUID())
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("경로변수 reportId가 비-UUID면 400 INVALID_REQUEST(500 아님)")
  void malformedUuidPathVariableReturns400() throws Exception {
    mockMvc.perform(get("/reports/{id}/proposals", "not-a-uuid")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }
}
