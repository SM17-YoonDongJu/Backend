package com.soma.backend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.dto.PasswordAuthResponse;
import com.soma.backend.domain.auth.dto.PasswordSignupRequest;
import com.soma.backend.domain.auth.service.PasswordLoginService;
import com.soma.backend.domain.auth.service.PasswordSignupService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

/**
 * 아이디·비밀번호 로그인 컨트롤러 슬라이스 테스트(이슈 #389).
 *
 * <p>검증 대상은 HTTP 계약이다 — 바디 {@code status} 문자열과 실제 HTTP 상태코드가 같은지
 * ({@code @ResponseStatus} 누락이 가장 쉬운 계약 파괴), 토큰이 바디가 아니라 쿠키로만 나가는지,
 * 요청·응답 키가 snake_case인지, 인증 없이 접근되는지({@code /auth/**} permitAll).
 *
 * <p>인증 주체를 심는 {@code @WithMockUser}·{@code @WithUserDetails}·{@code Authorization} 헤더는
 * 이 프로젝트에서 동작하지 않으므로 쓰지 않는다(인증이 필요 없는 경로라 해당 사항도 없다).
 */
@WebMvcTest(PasswordAuthController.class)
@ActiveProfiles("test")
@Import({
  SecurityConfig.class,
  JwtFilter.class,
  JwtProvider.class,
  CookieProvider.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class
})
@DisplayName("PasswordAuthController 슬라이스 테스트")
class PasswordAuthControllerTest {

  private static final String LOGIN_ID = "honggil";
  private static final String PASSWORD = "plainpassword1234";
  private static final String NAME = "홍길동";
  private static final String TOKEN_VALUE = "issued.jwt.value";
  private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PasswordSignupService passwordSignupService;

  @MockitoBean
  private PasswordLoginService passwordLoginService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private static String signupBody(String loginId, String password, String name) {
    return "{\"login_id\":\"" + loginId + "\",\"password\":\"" + password
        + "\",\"name\":\"" + name + "\"}";
  }

  private static String loginBody(String loginId, String password) {
    return "{\"login_id\":\"" + loginId + "\",\"password\":\"" + password + "\"}";
  }

  /** 서비스가 하는 일(access·refresh 쿠키 부착)을 모사한다 — 컨트롤러는 응답 객체를 건드리지 않는다. */
  private static void addAuthCookies(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE,
        ResponseCookie.from(CookieProvider.ACCESS_TOKEN_COOKIE, TOKEN_VALUE)
            .httpOnly(true).path("/").maxAge(Duration.ofMinutes(30)).build().toString());
    response.addHeader(HttpHeaders.SET_COOKIE,
        ResponseCookie.from(CookieProvider.REFRESH_TOKEN_COOKIE, TOKEN_VALUE)
            .httpOnly(true).path("/auth").maxAge(Duration.ofDays(14)).build().toString());
  }

  private void givenSignupIssuesCookies() {
    willAnswer(invocation -> {
      addAuthCookies(invocation.getArgument(0));
      return new PasswordAuthResponse(USER_ID, NAME, "USER");
    }).given(passwordSignupService).signup(any(HttpServletResponse.class), any());
  }

  private void givenLoginIssuesCookies() {
    willAnswer(invocation -> {
      addAuthCookies(invocation.getArgument(0));
      return new PasswordAuthResponse(USER_ID, NAME, "USER");
    }).given(passwordLoginService).login(any(HttpServletResponse.class), any());
  }

  @Test
  @DisplayName("가입 성공이면 HTTP 201과 바디 status \"201\"이 함께 나가고 쿠키가 부착된다")
  void signup_success_returns201WithMatchingBodyStatus() throws Exception {
    // Given
    givenSignupIssuesCookies();

    // When & Then
    mockMvc.perform(post("/auth/password/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupBody(LOGIN_ID, PASSWORD, NAME)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("201"))
        .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
        .andExpect(jsonPath("$.data.user_id").value(USER_ID.toString()))
        .andExpect(jsonPath("$.data.nickname").value(NAME))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(cookie().exists(CookieProvider.ACCESS_TOKEN_COOKIE))
        .andExpect(cookie().exists(CookieProvider.REFRESH_TOKEN_COOKIE))
        .andExpect(cookie().httpOnly(CookieProvider.ACCESS_TOKEN_COOKIE, true));
  }

  @Test
  @DisplayName("로그인 성공이면 HTTP 200과 바디 status \"200\"이 함께 나가고 쿠키가 부착된다")
  void login_success_returns200WithMatchingBodyStatus() throws Exception {
    // Given
    givenLoginIssuesCookies();

    // When & Then
    mockMvc.perform(post("/auth/password/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(LOGIN_ID, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.message").value("로그인이 완료되었습니다."))
        .andExpect(jsonPath("$.data.user_id").value(USER_ID.toString()))
        .andExpect(cookie().exists(CookieProvider.ACCESS_TOKEN_COOKIE));
  }

  @Test
  @DisplayName("토큰은 Set-Cookie로만 나가고 응답 바디에는 토큰 문자열이 들어가지 않는다")
  void login_doesNotLeakTokenInBody() throws Exception {
    // Given
    givenLoginIssuesCookies();

    // When
    String body = mockMvc.perform(post("/auth/password/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(LOGIN_ID, PASSWORD)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    // Then
    assertThat(body).doesNotContain(TOKEN_VALUE);
    assertThat(body).doesNotContain(CookieProvider.ACCESS_TOKEN_COOKIE);
    assertThat(body).doesNotContain(CookieProvider.REFRESH_TOKEN_COOKIE);
    assertThat(body).doesNotContain(PASSWORD);
  }

  @Test
  @DisplayName("요청 바디의 snake_case 키가 DTO로 역직렬화된다")
  void signup_snakeCaseRequestKeys_areDeserialized() throws Exception {
    // Given
    givenSignupIssuesCookies();

    // When
    mockMvc.perform(post("/auth/password/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupBody(LOGIN_ID, PASSWORD, NAME)))
        .andExpect(status().isCreated());

    // Then
    ArgumentCaptor<PasswordSignupRequest> captor =
        ArgumentCaptor.forClass(PasswordSignupRequest.class);
    then(passwordSignupService).should().signup(any(HttpServletResponse.class), captor.capture());
    assertThat(captor.getValue().loginId()).isEqualTo(LOGIN_ID);
    assertThat(captor.getValue().password()).isEqualTo(PASSWORD);
    assertThat(captor.getValue().name()).isEqualTo(NAME);
  }

  @Test
  @DisplayName("camelCase 키(loginId)로 보내면 계약 위반이라 400 BAD_REQUEST")
  void signup_camelCaseRequestKeys_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/auth/password/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"loginId\":\"honggil\",\"password\":\"" + PASSWORD + "\",\"name\":\"" + NAME + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

    then(passwordSignupService).shouldHaveNoInteractions();
  }

  @ParameterizedTest(name = "{3}")
  @CsvSource({
    "abc, plainpassword1234, 홍길동, 아이디가 4자 미만",
    "honggil홍, plainpassword1234, 홍길동, 아이디에 한글",
    "hong-gil, plainpassword1234, 홍길동, 아이디에 하이픈",
    "honggil, pass123, 홍길동, 비밀번호가 8자 미만",
    "honggil, plainpassword1234, '', 이름이 빈 문자열"
  })
  @DisplayName("가입 요청 검증 실패면 400 BAD_REQUEST이고 서비스를 호출하지 않는다")
  void signup_invalidRequest_returns400(
      String loginId, String password, String name, String caseName) throws Exception {
    // When & Then
    mockMvc.perform(post("/auth/password/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupBody(loginId, password, name)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

    then(passwordSignupService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("아이디 중복이면 409 DUPLICATE_RESOURCE")
  void signup_duplicateLoginId_returns409() throws Exception {
    // Given
    willThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE))
        .given(passwordSignupService).signup(any(HttpServletResponse.class), any());

    // When & Then
    mockMvc.perform(post("/auth/password/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupBody(LOGIN_ID, PASSWORD, NAME)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
  }

  @Test
  @DisplayName("로그인 실패는 401 LOGIN_FAILED이고 사유를 구분하지 않는 공통 메시지만 내려준다")
  void login_failure_returns401WithoutRevealingReason() throws Exception {
    // Given
    willThrow(new BusinessException(ErrorCode.LOGIN_FAILED))
        .given(passwordLoginService).login(any(HttpServletResponse.class), any());

    // When & Then
    mockMvc.perform(post("/auth/password/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(LOGIN_ID, "wrongpassword")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
        .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
  }

  @Test
  @DisplayName("로그인은 빈 아이디·비밀번호만 거르고(@NotBlank) 형식은 검증하지 않는다")
  void login_blankCredentials_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/auth/password/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody("", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

    then(passwordLoginService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("인증 쿠키 없이 호출해도 401이 아니다(/auth/** permitAll)")
  void login_withoutAuthentication_isPermitted() throws Exception {
    // Given
    given(passwordLoginService.login(any(HttpServletResponse.class), any()))
        .willReturn(new PasswordAuthResponse(USER_ID, NAME, "USER"));

    // When & Then — 쿠키·인증 헤더를 전혀 싣지 않는다
    mockMvc.perform(post("/auth/password/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(LOGIN_ID, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"));
  }

  @Test
  @DisplayName("가입도 인증 쿠키 없이 접근된다(/auth/** permitAll)")
  void signup_withoutAuthentication_isPermitted() throws Exception {
    // Given
    given(passwordSignupService.signup(any(HttpServletResponse.class), any()))
        .willReturn(new PasswordAuthResponse(USER_ID, NAME, "USER"));

    // When & Then
    mockMvc.perform(post("/auth/password/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(signupBody(LOGIN_ID, PASSWORD, NAME)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("201"));
  }
}
