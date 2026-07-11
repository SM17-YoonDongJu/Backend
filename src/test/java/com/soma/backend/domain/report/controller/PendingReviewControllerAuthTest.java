package com.soma.backend.domain.report.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.report.service.PendingReviewQueryService;
import com.soma.backend.domain.report.service.ReportHoldCommandService;
import com.soma.backend.domain.report.service.ReportReviewCommandService;
import com.soma.backend.domain.report.service.ReviewWorkspaceQueryService;
import com.soma.backend.domain.report.service.ReviewedReportQueryService;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;

/**
 * 검수 대시보드 엔드포인트의 인가 검증. @PreAuthorize(메서드 시큐리티)를 실제로 태우는
 * @WebMvcTest 슬라이스다.
 *
 * <p>미인증(401 LOGIN_REQUIRED)과 role 불일치(403 FORBIDDEN) 모두 메서드 시큐리티가 판정하고,
 * 그 예외를 GlobalExceptionHandler가 각각 401·403으로 매핑한다. 비로그인은
 * AuthenticationCredentialsNotFoundException → 401, 로그인했으나 role이 안 맞으면
 * AuthorizationDeniedException → 403이 된다.
 */
@WebMvcTest(
    controllers = {PendingReviewController.class, ReviewedReportController.class},
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtFilter.class))
@Import({PendingReviewControllerAuthTest.SecurityTestConfig.class, GlobalExceptionHandler.class})
class PendingReviewControllerAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PendingReviewQueryService pendingReviewQueryService;

  @MockitoBean
  private ReportHoldCommandService reportHoldCommandService;

  @MockitoBean
  private ReportReviewCommandService reportReviewCommandService;

  @MockitoBean
  private ReviewedReportQueryService reviewedReportQueryService;

  @MockitoBean
  private ReviewWorkspaceQueryService reviewWorkspaceQueryService;

  private static RequestPostProcessor as(String role) {
    CustomUserDetails principal = new CustomUserDetails(UUID.randomUUID(), role);
    Authentication auth = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    return authentication(auth);
  }

  @Nested
  @DisplayName("조회 엔드포인트(summary/목록)")
  class ReadEndpoints {

    @Test
    @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
    void unauthenticatedReturns401() throws Exception {
      mockMvc.perform(get("/reports/pending-review/summary"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("USER면 403 FORBIDDEN")
    void userReturns403() throws Exception {
      mockMvc.perform(get("/reports/pending-review/summary").with(as("USER")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("ADMIN이면 403 FORBIDDEN")
    void adminReturns403() throws Exception {
      mockMvc.perform(get("/reports/pending-review/summary").with(as("ADMIN")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 summary 200")
    void uncertificatedAdjusterSummary200() throws Exception {
      mockMvc.perform(get("/reports/pending-review/summary").with(as("UNCERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 목록 200")
    void uncertificatedAdjusterList200() throws Exception {
      mockMvc.perform(get("/reports/pending-review").with(as("UNCERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CERTIFICATED_ADJUSTER면 summary 200")
    void certificatedAdjusterSummary200() throws Exception {
      mockMvc.perform(get("/reports/pending-review/summary").with(as("CERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("상세 조회 엔드포인트(API#6)")
  class DetailEndpoint {

    private final UUID reportId = UUID.randomUUID();

    @Test
    @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
    void unauthenticatedReturns401() throws Exception {
      mockMvc.perform(get("/reports/{reportId}", reportId))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("USER면 403 FORBIDDEN")
    void userReturns403() throws Exception {
      mockMvc.perform(get("/reports/{reportId}", reportId).with(as("USER")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 200")
    void uncertificatedAdjuster200() throws Exception {
      mockMvc.perform(get("/reports/{reportId}", reportId).with(as("UNCERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("검수 화면 워크스페이스 엔드포인트(CERTIFICATED 전용)")
  class ReviewWorkspaceEndpoint {

    private final UUID reportId = UUID.randomUUID();

    @Test
    @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
    void unauthenticatedReturns401() throws Exception {
      mockMvc.perform(get("/reports/{reportId}/review", reportId))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 403(편집 화면은 CERTIFICATED만)")
    void uncertificatedAdjusterReturns403() throws Exception {
      mockMvc.perform(get("/reports/{reportId}/review", reportId).with(as("UNCERTIFICATED_ADJUSTER")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("CERTIFICATED_ADJUSTER면 200")
    void certificatedAdjuster200() throws Exception {
      mockMvc.perform(get("/reports/{reportId}/review", reportId).with(as("CERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("쓰기 엔드포인트(보류 추가/검수 반영)")
  class WriteEndpoints {

    private final UUID reportId = UUID.randomUUID();

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 보류 추가 403")
    void uncertificatedAdjusterHold403() throws Exception {
      mockMvc.perform(post("/reports/{reportId}/hold", reportId).with(as("UNCERTIFICATED_ADJUSTER")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 검수 반영 403")
    void uncertificatedAdjusterReview403() throws Exception {
      mockMvc.perform(patch("/reports/{reportId}", reportId)
              .with(as("UNCERTIFICATED_ADJUSTER"))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("CERTIFICATED_ADJUSTER면 보류 추가 통과(200)")
    void certificatedAdjusterHold200() throws Exception {
      mockMvc.perform(post("/reports/{reportId}/hold", reportId).with(as("CERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CERTIFICATED_ADJUSTER면 검수 반영 통과(200)")
    void certificatedAdjusterReview200() throws Exception {
      mockMvc.perform(patch("/reports/{reportId}", reportId)
              .with(as("CERTIFICATED_ADJUSTER"))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{}"))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("내 검수 내역(API#5)")
  class ReviewedReportsEndpoint {

    @Test
    @DisplayName("UNCERTIFICATED_ADJUSTER면 200")
    void uncertificatedAdjuster200() throws Exception {
      mockMvc.perform(get("/adjusters/me/reviewed-reports").with(as("UNCERTIFICATED_ADJUSTER")))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER면 403")
    void userReturns403() throws Exception {
      mockMvc.perform(get("/adjusters/me/reviewed-reports").with(as("USER")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
  }

  /**
   * 슬라이스에서 메서드 시큐리티를 활성화하고, 필터체인은 전부 permitAll(운영 SecurityConfig와 동일)로 두어
   * 401·403 모두 @PreAuthorize(메서드 시큐리티)에서만 나오게 한다.
   */
  @EnableWebSecurity
  @EnableMethodSecurity
  static class SecurityTestConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
      return http
          .csrf(AbstractHttpConfigurer::disable)
          .anonymous(AbstractHttpConfigurer::disable)
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .build();
    }
  }
}
