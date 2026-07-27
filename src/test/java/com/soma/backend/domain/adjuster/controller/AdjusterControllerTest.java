package com.soma.backend.domain.adjuster.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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

import com.soma.backend.domain.adjuster.dto.AdjusterDetailResponse;
import com.soma.backend.domain.adjuster.service.AdjusterProfileQueryService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;

/**
 * 사정사 공개 상세(GET /adjusters/{adjusterId}) 컨트롤러 슬라이스 테스트.
 *
 * <p>두 축을 검증한다. (1) 인가 — @PreAuthorize("isAuthenticated()")를 실제로 태워 비로그인은 401,
 * 인증된 고객(USER 포함)은 200(공개 프로필이라 롤 제한 없음). (2) JSON 계약 — 서비스를 목으로 두고 응답을
 * 실제 Jackson으로 직렬화해 snake_case 필드명·중첩 구조(consult_guide·certification)·verified·placeholder("")를
 * FE 계약대로 검증한다. 없는 사정사는 404(GlobalExceptionHandler가 ErrorResponse로 매핑).
 */
@WebMvcTest(
    controllers = AdjusterController.class,
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtFilter.class))
@Import({AdjusterControllerTest.SecurityTestConfig.class, GlobalExceptionHandler.class})
class AdjusterControllerTest {

  private static final UUID ADJUSTER_ID = UUID.randomUUID();
  private static final UUID VIEWER_ID = UUID.randomUUID();

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AdjusterProfileQueryService adjusterProfileQueryService;

  private static RequestPostProcessor as(String role) {
    CustomUserDetails principal = new CustomUserDetails(VIEWER_ID, role);
    Authentication auth = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    return authentication(auth);
  }

  @Test
  @DisplayName("비로그인이면 401 LOGIN_REQUIRED")
  void unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/adjusters/{adjusterId}", ADJUSTER_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("없는 사정사면 404 ADJUSTER_NOT_FOUND")
  void notFound_returns404() throws Exception {
    willThrow(new BusinessException(ErrorCode.ADJUSTER_NOT_FOUND))
        .given(adjusterProfileQueryService).getAdjusterDetail(any());

    mockMvc.perform(get("/adjusters/{adjusterId}", ADJUSTER_ID).with(as("USER")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ADJUSTER_NOT_FOUND"));
  }

  @Test
  @DisplayName("인증된 고객(USER)에게 공개 상세를 snake_case 계약대로 내린다")
  void authenticatedUser_returnsPublicDetail() throws Exception {
    given(adjusterProfileQueryService.getAdjusterDetail(any())).willReturn(populatedDetail());

    mockMvc.perform(get("/adjusters/{adjusterId}", ADJUSTER_ID).with(as("USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.adjuster_id").value(ADJUSTER_ID.toString()))
        .andExpect(jsonPath("$.data.nickname").value("김사정"))
        .andExpect(jsonPath("$.data.avatar_url").value("https://cdn/a.png"))
        .andExpect(jsonPath("$.data.headline").value("장해등급 재산정 전문"))
        .andExpect(jsonPath("$.data.activity_region").value("서울·경기"))
        .andExpect(jsonPath("$.data.specialties[0]").value("교통사고"))
        .andExpect(jsonPath("$.data.careers[0].period").value("2018~2022"))
        .andExpect(jsonPath("$.data.careers[0].company").value("OO손해사정법인"))
        .andExpect(jsonPath("$.data.career").value(7))
        .andExpect(jsonPath("$.data.average_rating").value(4.5))
        .andExpect(jsonPath("$.data.review_count").value(5))
        .andExpect(jsonPath("$.data.recent_reviews", hasSize(1)))
        .andExpect(jsonPath("$.data.recent_reviews[0].item").value("traffic"))
        .andExpect(jsonPath("$.data.recent_reviews[0].content").value("빠르고 친절했습니다."))
        .andExpect(jsonPath("$.data.completed_consult_count").value(4))
        .andExpect(jsonPath("$.data.handled_case_count").value(9))
        .andExpect(jsonPath("$.data.verified").value(true))
        .andExpect(jsonPath("$.data.consult_guide.method").value("대면, 비대면"))
        .andExpect(jsonPath("$.data.consult_guide.initial_consult").value(""))
        .andExpect(jsonPath("$.data.consult_guide.fee_basis").value(""))
        .andExpect(jsonPath("$.data.certification.registration_no").value("제2026-0412호"))
        .andExpect(jsonPath("$.data.certification.verified_at").value("2026-01-01T00:00:00"));

    verify(adjusterProfileQueryService).getAdjusterDetail(eq(ADJUSTER_ID));
  }

  private static AdjusterDetailResponse populatedDetail() {
    return new AdjusterDetailResponse(
        ADJUSTER_ID,
        "김사정",
        "https://cdn/a.png",
        "장해등급 재산정 전문",
        "서울·경기",
        "교통사고 전문 손해사정사입니다.",
        List.of("교통사고", "상해"),
        List.of(new AdjusterDetailResponse.CareerItem("2018~2022", "OO손해사정법인")),
        7,
        4.5,
        5,
        List.of(new AdjusterDetailResponse.RecentReview(
            "노글리", 5, "traffic", LocalDateTime.of(2026, 1, 2, 0, 0), "빠르고 친절했습니다.")),
        4,
        9L,
        true,
        new AdjusterDetailResponse.ConsultGuide("대면, 비대면", "", ""),
        new AdjusterDetailResponse.Certification("제2026-0412호", LocalDateTime.of(2026, 1, 1, 0, 0)));
  }

  /**
   * 슬라이스에서 메서드 시큐리티를 활성화하고, 필터체인은 전부 permitAll로 두어 401은 @PreAuthorize
   * (isAuthenticated())에서만 나오게 한다(AdjusterProfileControllerTest와 동일 관례).
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
