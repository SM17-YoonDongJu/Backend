package com.soma.backend.domain.adjuster.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

import com.soma.backend.domain.adjuster.dto.AdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.dto.CreateAdjusterApplicationResponse;
import com.soma.backend.domain.adjuster.service.AdjusterApplicationCommandService;
import com.soma.backend.domain.adjuster.service.AdjusterApplicationQueryService;
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

@WebMvcTest(AdjusterApplicationController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("AdjusterApplicationController 슬라이스 테스트")
class AdjusterApplicationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AdjusterApplicationCommandService commandService;
  @MockitoBean
  private AdjusterApplicationQueryService queryService;
  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private String validBody() {
    return "{"
        + "\"name\":\"홍길동\",\"phone\":\"010-1234-5678\",\"specialties\":[\"신체\"],"
        + "\"license_no\":\"제2024-0001호\","
        + "\"affiliation\":\"INDEPENDENT\",\"region\":\"서울 송파\","
        + "\"registration_image_url\":\"https://x/reg.pdf\"}";
  }

  @Test
  @DisplayName("POST-인증된 사용자가 정상 신청하면 201과 PENDING을 반환한다")
  void apply_returns201() throws Exception {
    UUID userId = UUID.randomUUID();
    given(commandService.apply(eq(userId), any()))
        .willReturn(new CreateAdjusterApplicationResponse(UUID.randomUUID(), "PENDING"));

    mockMvc.perform(post("/users/adjuster-applications").with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("201"))
        .andExpect(jsonPath("$.message").value("자격 신청이 접수되었습니다."))
        .andExpect(jsonPath("$.data.status").value("PENDING"));
  }

  @Test
  @DisplayName("POST-인증이 없으면 401 LOGIN_REQUIRED")
  void apply_noAuth_returns401() throws Exception {
    mockMvc.perform(post("/users/adjuster-applications")
            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("POST-필수 필드가 누락되면 400")
  void apply_invalidBody_returns400() throws Exception {
    UUID userId = UUID.randomUUID();
    String body = "{\"specialties\":[\"신체\"]}";

    mockMvc.perform(post("/users/adjuster-applications").with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST-중복 신청이면 409 DUPLICATE_RESOURCE")
  void apply_duplicate_returns409() throws Exception {
    UUID userId = UUID.randomUUID();
    willThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE))
        .given(commandService).apply(eq(userId), any());

    mockMvc.perform(post("/users/adjuster-applications").with(authenticatedAs(userId))
            .contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
  }

  @Test
  @DisplayName("GET /me-신청이 있으면 200과 상태를 반환한다")
  void getMyApplication_returns200() throws Exception {
    UUID userId = UUID.randomUUID();
    AdjusterApplicationResponse response = new AdjusterApplicationResponse(
        UUID.randomUUID(), "PENDING", null, "홍길동", "010-1234-5678", List.of("신체"), "제2024-0001호",
        List.of(), null, null);
    given(queryService.getMyApplication(userId)).willReturn(response);

    mockMvc.perform(get("/users/adjuster-applications/me").with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.name").value("홍길동"));
  }

  @Test
  @DisplayName("GET /me-신청 이력이 없으면 404 POST_NOT_FOUND")
  void getMyApplication_notFound_returns404() throws Exception {
    UUID userId = UUID.randomUUID();
    willThrow(new BusinessException(ErrorCode.POST_NOT_FOUND))
        .given(queryService).getMyApplication(userId);

    mockMvc.perform(get("/users/adjuster-applications/me").with(authenticatedAs(userId)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }
}
