package com.soma.backend.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import com.soma.backend.domain.auth.dto.OAuthCallbackResponse;
import com.soma.backend.domain.auth.dto.RegisterRequest;
import com.soma.backend.domain.auth.dto.RegisterResponse;
import com.soma.backend.domain.auth.service.AuthLogoutService;
import com.soma.backend.domain.auth.service.AuthRegisterService;
import com.soma.backend.domain.auth.service.AuthReissueService;
import com.soma.backend.domain.auth.service.OAuthLoginService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("AuthController 슬라이스 테스트")
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private OAuthLoginService oAuthLoginService;

  @MockitoBean
  private AuthRegisterService authRegisterService;

  @MockitoBean
  private AuthReissueService authReissueService;

  @MockitoBean
  private AuthLogoutService authLogoutService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private void addAccessCookie(HttpServletResponse response, Duration maxAge) {
    ResponseCookie cookie = ResponseCookie.from(CookieProvider.ACCESS_TOKEN_COOKIE, "token-value")
        .httpOnly(true)
        .path("/")
        .maxAge(maxAge)
        .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  /**
   * register 요청 바디를 만든다. 케이스마다 달라지는 이름·지역 필드만 {@code nameAndRegion}으로 받고
   * (각 필드는 뒤에 쉼표를 포함한 조각으로 전달), 나머지 필수 필드는 공통값으로 채운다.
   */
  private String registerBody(String nameAndRegion) {
    return "{\"provider\":\"kakao\",\"social_token\":\"ticket\"," + nameAndRegion
        + "\"birth_date\":\"1990-01-01\",\"phone_number\":\"010-1234-5678\","
        + "\"gender\":\"남\",\"user_type\":\"insured_person\"}";
  }

  /** 컨트롤러가 서비스로 넘긴 요청 DTO를 캡처한다(역직렬화 결과 = API 계약 검증용). */
  private RegisterRequest capturedRegisterRequest() {
    ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
    then(authRegisterService).should().register(any(HttpServletResponse.class), captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("콜백-기존회원이면 200과 함께 access_token 쿠키를 발급하고 is_new_user=false")
  void callback_existingUser_setsCookieAndReturnsFalse() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    willAnswer(invocation -> {
      addAccessCookie(invocation.getArgument(0), Duration.ofMinutes(30));
      return OAuthCallbackResponse.existingUser(userId);
    }).given(oAuthLoginService)
        .handleCallback(any(HttpServletResponse.class), eq("kakao"), eq("code"), any(), any());

    // When & Then
    mockMvc.perform(get("/auth/oauth2/{provider}/callback", "kakao")
            .param("code", "code")
            .param("state", "state"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.is_new_user").value(false))
        .andExpect(jsonPath("$.data.user_id").value(userId.toString()))
        .andExpect(jsonPath("$.data.signup_ticket").doesNotExist())
        .andExpect(cookie().exists(CookieProvider.ACCESS_TOKEN_COOKIE));
  }

  @Test
  @DisplayName("콜백-신규회원이면 200과 함께 쿠키 없이 signup_ticket을 반환하고 is_new_user=true")
  void callback_newUser_returnsTicketWithoutCookie() throws Exception {
    // Given
    given(oAuthLoginService.handleCallback(any(HttpServletResponse.class), eq("naver"), eq("code"), any(), any()))
        .willReturn(OAuthCallbackResponse.newUser("signup-ticket-jwt"));

    // When & Then
    mockMvc.perform(get("/auth/oauth2/{provider}/callback", "naver")
            .param("code", "code"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.is_new_user").value(true))
        .andExpect(jsonPath("$.data.signup_ticket").value("signup-ticket-jwt"))
        .andExpect(cookie().doesNotExist(CookieProvider.ACCESS_TOKEN_COOKIE));
  }

  @Test
  @DisplayName("콜백-필수 파라미터 code 누락이면 400 MISSING_REQUIRED_FIELD")
  void callback_missingCode_returns400() throws Exception {
    mockMvc.perform(get("/auth/oauth2/{provider}/callback", "kakao"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"));
  }

  @Test
  @DisplayName("register 성공이면 201과 함께 status·message·snake_case 본문을 반환하고 쿠키를 발급한다")
  void register_success_returns201WithBodyAndCookie() throws Exception {
    // Given
    UUID userId = UUID.randomUUID();
    willAnswer(invocation -> {
      addAccessCookie(invocation.getArgument(0), Duration.ofMinutes(30));
      return new RegisterResponse(userId, "nickname", "USER");
    }).given(authRegisterService).register(any(HttpServletResponse.class), any());

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"홍길동\",\"region\":\"서울·경기\",")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("201"))
        .andExpect(jsonPath("$.message").value("회원가입이 완료되었습니다."))
        .andExpect(jsonPath("$.data.user_id").value(userId.toString()))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(cookie().exists(CookieProvider.ACCESS_TOKEN_COOKIE));

    RegisterRequest captured = capturedRegisterRequest();
    assertThat(captured.name()).isEqualTo("홍길동");
    assertThat(captured.region()).isEqualTo("서울·경기");
  }

  @Test
  @DisplayName("register-구 클라이언트가 nickname 키만 보내도 별칭으로 수용해 201을 반환한다")
  void register_legacyNicknameKey_isAcceptedAsAlias() throws Exception {
    // Given
    given(authRegisterService.register(any(HttpServletResponse.class), any()))
        .willReturn(new RegisterResponse(UUID.randomUUID(), "홍길동", "USER"));

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"nickname\":\"홍길동\",")))
        .andExpect(status().isCreated());

    assertThat(capturedRegisterRequest().name()).isEqualTo("홍길동");
  }

  @Test
  @DisplayName("register-name과 nickname을 동시에 보내면 JSON에서 나중에 온 값이 이긴다(동시 전송 금지 계약)")
  void register_bothNameAndNickname_lastOneWins() throws Exception {
    // Given
    given(authRegisterService.register(any(HttpServletResponse.class), any()))
        .willReturn(new RegisterResponse(UUID.randomUUID(), "홍길동", "USER"));

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"이름A\",\"nickname\":\"이름B\",")))
        .andExpect(status().isCreated());

    assertThat(capturedRegisterRequest().name()).isEqualTo("이름B");
  }

  @Test
  @DisplayName("register-region을 보내지 않아도 201이며 region은 null로 전달된다")
  void register_withoutRegion_passesNull() throws Exception {
    // Given
    given(authRegisterService.register(any(HttpServletResponse.class), any()))
        .willReturn(new RegisterResponse(UUID.randomUUID(), "홍길동", "USER"));

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"홍길동\",")))
        .andExpect(status().isCreated());

    assertThat(capturedRegisterRequest().region()).isNull();
  }

  @Test
  @DisplayName("register-region이 100자를 넘으면 400 BAD_REQUEST")
  void register_regionTooLong_returns400() throws Exception {
    // Given
    String longRegion = "서".repeat(101);

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"홍길동\",\"region\":\"" + longRegion + "\",")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("register-전화번호 중복이면 409 DUPLICATE_RESOURCE")
  void register_duplicate_returns409() throws Exception {
    // Given
    willThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE))
        .given(authRegisterService).register(any(HttpServletResponse.class), any());

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"홍길동\",\"region\":\"서울\",")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
  }

  @Test
  @DisplayName("register-티켓 위조/불일치면 401 INVALID_TOKEN")
  void register_invalidTicket_returns401() throws Exception {
    // Given
    willThrow(new BusinessException(ErrorCode.INVALID_TOKEN))
        .given(authRegisterService).register(any(HttpServletResponse.class), any());

    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"홍길동\",\"region\":\"서울\",")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  @DisplayName("register-@Valid 실패(빈 이름)면 400 BAD_REQUEST")
  void register_validationFail_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("\"name\":\"\",")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("register-name·nickname을 모두 보내지 않으면 400 BAD_REQUEST")
  void register_missingNameAndNickname_returns400() throws Exception {
    // When & Then
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody("")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @DisplayName("logout은 인증 없이도 200이며 만료 쿠키(Max-Age=0)를 내려준다")
  void logout_idempotent_returns200WithExpiredCookie() throws Exception {
    // Given
    willAnswer(invocation -> {
      addAccessCookie(invocation.getArgument(1), Duration.ZERO);
      return null;
    }).given(authLogoutService).logout(any(), any(HttpServletResponse.class), any());

    // When & Then
    mockMvc.perform(post("/auth/logout"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(cookie().maxAge(CookieProvider.ACCESS_TOKEN_COOKIE, 0));
  }

  @Test
  @DisplayName("reissue 성공이면 200과 함께 새 쿠키를 발급한다")
  void reissue_success_returns200() throws Exception {
    // Given
    willAnswer(invocation -> {
      addAccessCookie(invocation.getArgument(1), Duration.ofMinutes(30));
      return null;
    }).given(authReissueService).reissue(any(), any(HttpServletResponse.class));

    // When & Then
    mockMvc.perform(post("/auth/reissue"))
        .andExpect(status().isOk())
        .andExpect(cookie().exists(CookieProvider.ACCESS_TOKEN_COOKIE));
  }

  @Test
  @DisplayName("reissue-refresh 쿠키가 없으면 401 LOGIN_REQUIRED")
  void reissue_noCookie_returns401() throws Exception {
    // Given
    willThrow(new BusinessException(ErrorCode.LOGIN_REQUIRED))
        .given(authReissueService).reissue(any(), any(HttpServletResponse.class));

    // When & Then
    mockMvc.perform(post("/auth/reissue"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }
}
