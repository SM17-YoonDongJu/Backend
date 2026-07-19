package com.soma.backend.domain.report.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import com.soma.backend.domain.report.dto.Pagination;
import com.soma.backend.domain.report.dto.ProposalListResponse;
import com.soma.backend.domain.report.service.ProposalQueryService;
import com.soma.backend.domain.report.service.ReportCommandService;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CurrentUserIdArgumentResolver;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * @CurrentUserId 인가 가드(401) + 로그인 통과(200) 검증. CurrentUserIdArgumentResolver +
 * GlobalExceptionHandler를 실제로 태운다(DB·SecurityFilterChain 없이 SecurityContext 직접 세팅).
 */
class ReportControllerAuthTest {

  private MockMvc mockMvc;
  private ProposalQueryService proposalQueryService;

  @BeforeEach
  void setUp() {
    ReportCommandService reportCommandService = Mockito.mock(ReportCommandService.class);
    proposalQueryService = Mockito.mock(ProposalQueryService.class);
    ReportController controller =
        new ReportController(reportCommandService, proposalQueryService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateAsUser() {
    CustomUserDetails principal = new CustomUserDetails(UUID.randomUUID(), "USER");
    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Test
  @DisplayName("비로그인이면 GET /reports/{id}/proposals는 401 LOGIN_REQUIRED")
  void unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/reports/" + UUID.randomUUID() + "/proposals"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("로그인 사용자는 GET /reports/{id}/proposals 200")
  void authenticatedProposalsReturns200() throws Exception {
    authenticateAsUser();
    given(proposalQueryService.getProposals(any(), any(), anyInt(), anyInt()))
        .willReturn(new ProposalListResponse(List.of(), new Pagination(1, 10, 0, 0, false)));

    mockMvc.perform(get("/reports/" + UUID.randomUUID() + "/proposals")).andExpect(status().isOk());
  }
}
