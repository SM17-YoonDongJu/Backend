package com.soma.backend.domain.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import com.soma.backend.domain.user.dto.UserInsuranceListResponse;
import com.soma.backend.domain.user.service.UserInsuranceQueryService;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

@WebMvcTest(UserInsuranceController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("UserInsuranceController 슬라이스 테스트")
class UserInsuranceControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserInsuranceQueryService userInsuranceQueryService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("GET /users/me/insurances-인증된 사용자면 200과 보험 목록을 snake_case로 반환한다(match_status 미포함)")
  void getMyInsurances_authenticated_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    UserInsuranceListResponse response = new UserInsuranceListResponse(List.of(
        new UserInsuranceListResponse.Item(
            firstId,
            "OO손해보험",
            "무배당 행복드림 종합보험",
            "100-2024-558***",
            LocalDate.of(2024, 3, 15),
            List.of("상해후유장해", "골절진단비", "입원일당"),
            "https://cdn.example.com/first.pdf"),
        new UserInsuranceListResponse.Item(
            secondId,
            "△△생명",
            "든든 의료실비보험",
            "220-2023-114***",
            LocalDate.of(2023, 8, 2),
            List.of("실손의료비", "수술비"),
            null)));
    given(userInsuranceQueryService.getMyInsurances(userId)).willReturn(response);

    // When & Then
    mockMvc.perform(get("/users/me/insurances").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.message").value("정상 처리되었습니다."))
        .andExpect(jsonPath("$.data.list[0].id").value(firstId.toString()))
        .andExpect(jsonPath("$.data.list[0].insurer_name").value("OO손해보험"))
        .andExpect(jsonPath("$.data.list[0].product_name").value("무배당 행복드림 종합보험"))
        .andExpect(jsonPath("$.data.list[0].policy_no").value("100-2024-558***"))
        .andExpect(jsonPath("$.data.list[0].enrolled_at").value("2024-03-15"))
        .andExpect(jsonPath("$.data.list[0].coverages[0]").value("상해후유장해"))
        .andExpect(jsonPath("$.data.list[0].policy_file_url").value("https://cdn.example.com/first.pdf"))
        .andExpect(jsonPath("$.data.list[0].match_status").doesNotExist())
        .andExpect(jsonPath("$.data.list[1].policy_file_url").value(nullValue()));
  }

  @Test
  @DisplayName("GET /users/me/insurances-보유 보험이 없으면 200과 빈 목록을 반환한다")
  void getMyInsurances_empty_returns200() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    given(userInsuranceQueryService.getMyInsurances(userId))
        .willReturn(new UserInsuranceListResponse(List.of()));

    // When & Then
    mockMvc.perform(get("/users/me/insurances").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.list").isArray())
        .andExpect(jsonPath("$.data.list").isEmpty());
  }

  @Test
  @DisplayName("GET /users/me/insurances-인증이 없으면 401 LOGIN_REQUIRED")
  void getMyInsurances_noAuth_returns401() throws Exception {
    mockMvc.perform(get("/users/me/insurances"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }
}
