package com.soma.backend.domain.adjuster.controller;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
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

import com.soma.backend.domain.adjuster.service.AdjusterHomeQueryService;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;

/**
 * 사정사 홈 대시보드(GET /adjusters/me/home) 인가 검증. @PreAuthorize(메서드 시큐리티)를 실제로 태우는
 * @WebMvcTest 슬라이스다. 조회이므로 CERTIFICATED·UNCERTIFICATED_ADJUSTER 모두 허용, 그 외는 403,
 * 비로그인은 401이다(모두 GlobalExceptionHandler가 ErrorResponse로 매핑).
 */
@WebMvcTest(
    controllers = AdjusterHomeController.class,
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtFilter.class))
@Import({AdjusterHomeControllerAuthTest.SecurityTestConfig.class, GlobalExceptionHandler.class})
class AdjusterHomeControllerAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AdjusterHomeQueryService adjusterHomeQueryService;

  private static RequestPostProcessor as(String role) {
    CustomUserDetails principal = new CustomUserDetails(UUID.randomUUID(), role);
    Authentication auth = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    return authentication(auth);
  }

  @Test
  @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
  void unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/adjusters/me/home"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("USER면 403 FORBIDDEN")
  void userReturns403() throws Exception {
    mockMvc.perform(get("/adjusters/me/home").with(as("USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("ADMIN이면 403 FORBIDDEN")
  void adminReturns403() throws Exception {
    mockMvc.perform(get("/adjusters/me/home").with(as("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("UNCERTIFICATED_ADJUSTER면 200")
  void uncertificatedAdjuster200() throws Exception {
    mockMvc.perform(get("/adjusters/me/home").with(as("UNCERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("CERTIFICATED_ADJUSTER면 200")
  void certificatedAdjuster200() throws Exception {
    mockMvc.perform(get("/adjusters/me/home").with(as("CERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk());
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
