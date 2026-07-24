package com.soma.backend.domain.adjuster.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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

import com.soma.backend.domain.adjuster.dto.AdjusterMyPageResponse;
import com.soma.backend.domain.adjuster.dto.AdjusterProfileResponse;
import com.soma.backend.domain.adjuster.service.AdjusterProfileQueryService;
import com.soma.backend.global.exception.GlobalExceptionHandler;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;

/**
 * 사정사 마이페이지/프로필 조회(GET /adjusters/me/profile, /mypage) 컨트롤러 슬라이스 테스트.
 *
 * <p>두 축을 검증한다. (1) 인가 — @PreAuthorize(메서드 시큐리티)를 실제로 태워 조회는 두 사정사 롤 모두
 * 허용(200), 그 외(USER·ADMIN)는 403, 비로그인은 401(GlobalExceptionHandler가 ErrorResponse로 매핑).
 * (2) JSON 계약 — 서비스를 목으로 두고 응답을 실제 Jackson으로 직렬화해 snake_case 필드명·중첩 구조·null 키
 * 유지(email/average_rating 등)·recent_reviews 상한을 노션 스펙대로 검증한다.
 */
@WebMvcTest(
    controllers = AdjusterProfileController.class,
    excludeFilters =
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtFilter.class))
@Import({AdjusterProfileControllerTest.SecurityTestConfig.class, GlobalExceptionHandler.class})
class AdjusterProfileControllerTest {

  private static final UUID PRINCIPAL_ID = UUID.randomUUID();

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AdjusterProfileQueryService adjusterProfileQueryService;

  private static RequestPostProcessor as(String role) {
    CustomUserDetails principal = new CustomUserDetails(PRINCIPAL_ID, role);
    Authentication auth = new UsernamePasswordAuthenticationToken(
        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    return authentication(auth);
  }

  // ---------- 인가 (GET /adjusters/me/profile) ----------

  @Test
  @DisplayName("GET /profile: 비로그인이면 401 LOGIN_REQUIRED")
  void profile_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/adjusters/me/profile"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("GET /profile: USER면 403 FORBIDDEN")
  void profile_user_returns403() throws Exception {
    mockMvc.perform(get("/adjusters/me/profile").with(as("USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("GET /profile: ADMIN이면 403 FORBIDDEN")
  void profile_admin_returns403() throws Exception {
    mockMvc.perform(get("/adjusters/me/profile").with(as("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("GET /profile: UNCERTIFICATED_ADJUSTER도 조회는 200")
  void profile_uncertificatedAdjuster_returns200() throws Exception {
    given(adjusterProfileQueryService.getProfile(any())).willReturn(populatedProfile());

    mockMvc.perform(get("/adjusters/me/profile").with(as("UNCERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk());
  }

  // ---------- 인가 (GET /adjusters/me/mypage) ----------

  @Test
  @DisplayName("GET /mypage: 비로그인이면 401 LOGIN_REQUIRED")
  void mypage_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/adjusters/me/mypage"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("GET /mypage: USER면 403 FORBIDDEN")
  void mypage_user_returns403() throws Exception {
    mockMvc.perform(get("/adjusters/me/mypage").with(as("USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("GET /mypage: ADMIN이면 403 FORBIDDEN")
  void mypage_admin_returns403() throws Exception {
    mockMvc.perform(get("/adjusters/me/mypage").with(as("ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  // ---------- JSON 계약 (GET /adjusters/me/profile) ----------

  @Test
  @DisplayName("GET /profile: 인증 사정사에게 flat 응답을 principal.userId로 조회해 snake_case로 내린다")
  void profile_certificatedAdjuster_returnsFlatContract() throws Exception {
    given(adjusterProfileQueryService.getProfile(any())).willReturn(populatedProfile());

    mockMvc.perform(get("/adjusters/me/profile").with(as("CERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.adjuster_id").value(PRINCIPAL_ID.toString()))
        .andExpect(jsonPath("$.data.nickname").value("김사정"))
        .andExpect(jsonPath("$.data.avatar_url").value("https://cdn/a.png"))
        .andExpect(jsonPath("$.data.activity_region[0]").value("서울"))
        .andExpect(jsonPath("$.data.activity_region[1]").value("경기"))
        .andExpect(jsonPath("$.data.specialties[0]").value("교통사고"))
        .andExpect(jsonPath("$.data.careers[0].period").value("2018~2022"))
        .andExpect(jsonPath("$.data.careers[0].company").value("OO손해사정법인"))
        .andExpect(jsonPath("$.data.career").value(7))
        .andExpect(jsonPath("$.data.average_rating").value(4.5))
        .andExpect(jsonPath("$.data.review_count").value(5))
        .andExpect(jsonPath("$.data.recent_reviews", hasSize(2)))
        .andExpect(jsonPath("$.data.recent_reviews[0].item").value("traffic"))
        .andExpect(jsonPath("$.data.recent_reviews[0].reviewed_at").value("2026-01-02T00:00:00"))
        .andExpect(jsonPath("$.data.recent_reviews[1]", hasKey("item")))
        .andExpect(jsonPath("$.data.recent_reviews[1]", hasKey("content")))
        .andExpect(jsonPath("$.data.completed_consult_count").value(4))
        .andExpect(jsonPath("$.data.handled_case_count").value(9))
        .andExpect(jsonPath("$.data.pending_review_count").value(7));

    verify(adjusterProfileQueryService).getProfile(PRINCIPAL_ID);
  }

  @Test
  @DisplayName("GET /profile: 신규 사정사면 average_rating은 0.0(number), recent_reviews는 빈 배열")
  void profile_newAdjuster_zeroRating() throws Exception {
    given(adjusterProfileQueryService.getProfile(any())).willReturn(emptyProfile());

    mockMvc.perform(get("/adjusters/me/profile").with(as("CERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.average_rating").value(0.0))
        .andExpect(jsonPath("$.data.review_count").value(0))
        .andExpect(jsonPath("$.data.recent_reviews", hasSize(0)))
        .andExpect(jsonPath("$.data.specialties", hasSize(0)))
        .andExpect(jsonPath("$.data.careers", hasSize(0)))
        .andExpect(jsonPath("$.data.activity_region", hasSize(0)));
  }

  // ---------- JSON 계약 (GET /adjusters/me/mypage) ----------

  @Test
  @DisplayName("GET /mypage: 4개 섹션을 principal.userId로 조회해 snake_case 중첩 구조로 내린다")
  void mypage_certificatedAdjuster_returnsFourSections() throws Exception {
    given(adjusterProfileQueryService.getMyPage(any())).willReturn(populatedMyPage());

    mockMvc.perform(get("/adjusters/me/mypage").with(as("CERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.profile.nickname").value("김사정"))
        .andExpect(jsonPath("$.data.profile", hasKey("email")))
        .andExpect(jsonPath("$.data.profile.email").doesNotExist())
        .andExpect(jsonPath("$.data.profile.role").value("CERTIFICATED_ADJUSTER"))
        .andExpect(jsonPath("$.data.profile.activity_region[0]").value("서울"))
        .andExpect(jsonPath("$.data.stats.average_rating").value(4.9))
        .andExpect(jsonPath("$.data.stats.review_count").value(12))
        .andExpect(jsonPath("$.data.stats.total_completed_count").value(10))
        .andExpect(jsonPath("$.data.stats.consultation_conversion_rate").value(60))
        .andExpect(jsonPath("$.data.monthly_activity.completed_count").value(3))
        .andExpect(jsonPath("$.data.monthly_activity.consultation_converted_count").value(2))
        .andExpect(jsonPath("$.data.monthly_activity.average_rating").value(4.9))
        .andExpect(jsonPath("$.data.certification.license_no").value("제2026-0412호"))
        .andExpect(jsonPath("$.data.certification.created_at").value("2026-01-01T00:00:00"));

    verify(adjusterProfileQueryService).getMyPage(PRINCIPAL_ID);
  }

  @Test
  @DisplayName("GET /mypage: 신규 사정사면 평점·전환율은 0(number), 카운트는 0")
  void mypage_newAdjuster_zeroRules() throws Exception {
    given(adjusterProfileQueryService.getMyPage(any())).willReturn(emptyMyPage());

    mockMvc.perform(get("/adjusters/me/mypage").with(as("UNCERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profile.role").value("UNCERTIFICATED_ADJUSTER"))
        .andExpect(jsonPath("$.data.stats.average_rating").value(0.0))
        .andExpect(jsonPath("$.data.stats.consultation_conversion_rate").value(0))
        .andExpect(jsonPath("$.data.stats.review_count").value(0))
        .andExpect(jsonPath("$.data.stats.total_completed_count").value(0))
        .andExpect(jsonPath("$.data.monthly_activity.completed_count").value(0))
        .andExpect(jsonPath("$.data.monthly_activity.consultation_converted_count").value(0))
        .andExpect(jsonPath("$.data.monthly_activity.average_rating").value(0.0));
  }

  // ---------- 픽스처 ----------

  private static AdjusterProfileResponse populatedProfile() {
    return new AdjusterProfileResponse(
        PRINCIPAL_ID,
        "김사정",
        "장해등급 재산정 전문",
        "https://cdn/a.png",
        List.of("서울", "경기"),
        "교통사고 전문 손해사정사입니다.",
        List.of("교통사고", "상해"),
        List.of(new AdjusterProfileResponse.CareerItem("2018~2022", "OO손해사정법인")),
        7,
        4.5,
        5,
        List.of(
            new AdjusterProfileResponse.RecentReview(
                "노글리", 5, "traffic", LocalDateTime.of(2026, 1, 2, 0, 0), "빠르고 친절했습니다."),
            new AdjusterProfileResponse.RecentReview(
                "무명", 4, null, LocalDateTime.of(2026, 1, 1, 0, 0), null)),
        4,
        9L,
        7,
        LocalDateTime.of(2026, 1, 3, 0, 0));
  }

  private static AdjusterProfileResponse emptyProfile() {
    return new AdjusterProfileResponse(
        PRINCIPAL_ID, "새사정", null, null, List.of(), null, List.of(), List.of(),
        null, 0.0, 0, List.of(), 0, 0L, 0, null);
  }

  private static AdjusterMyPageResponse populatedMyPage() {
    return new AdjusterMyPageResponse(
        new AdjusterMyPageResponse.Profile(
            "김사정", null, "https://cdn/a.png", "장해등급 재산정 전문",
            List.of("교통사고", "상해"), 7, List.of("서울", "경기"), "CERTIFICATED_ADJUSTER"),
        new AdjusterMyPageResponse.Stats(4.9, 12, 10L, 60),
        new AdjusterMyPageResponse.MonthlyActivity(3L, 2L, 4.9),
        new AdjusterMyPageResponse.Certification(
            "제2026-0412호", List.of("서울", "경기"), LocalDateTime.of(2026, 1, 1, 0, 0)));
  }

  private static AdjusterMyPageResponse emptyMyPage() {
    return new AdjusterMyPageResponse(
        new AdjusterMyPageResponse.Profile(
            "새사정", null, null, null, List.of(), null, List.of(), "UNCERTIFICATED_ADJUSTER"),
        new AdjusterMyPageResponse.Stats(0.0, 0, 0L, 0),
        new AdjusterMyPageResponse.MonthlyActivity(0L, 0L, 0.0),
        new AdjusterMyPageResponse.Certification(null, List.of(), LocalDateTime.of(2026, 2, 1, 0, 0)));
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
