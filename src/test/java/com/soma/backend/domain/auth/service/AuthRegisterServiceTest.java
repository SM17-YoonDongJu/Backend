package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.dto.RegisterRequest;
import com.soma.backend.domain.auth.dto.RegisterResponse;
import com.soma.backend.domain.auth.entity.SocialAccount;
import com.soma.backend.domain.auth.repository.SocialAccountRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.global.security.crypto.PiiHmac;
import com.soma.backend.infra.redis.AppleRefreshStagingRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRegisterService 단위 테스트")
class AuthRegisterServiceTest {

  @InjectMocks
  private AuthRegisterService authRegisterService;

  @Mock
  private SignupTicketProvider signupTicketProvider;

  @Mock
  private UserRepository userRepository;

  @Mock
  private SocialAccountRepository socialAccountRepository;

  @Mock
  private AuthTokenService authTokenService;

  @Mock
  private AppleRefreshStagingRepository appleRefreshStagingRepository;

  @Mock
  private PiiHmac piiHmac;

  @Mock
  private HttpServletResponse response;

  private static final String PHONE = "010-1234-5678";

  private RegisterRequest request(String userType) {
    return request(userType, "서울·경기");
  }

  private RegisterRequest request(String userType, @Nullable String region) {
    return new RegisterRequest(
        "kakao", "ticket", "홍길동", LocalDate.of(1990, 1, 1), PHONE, "남", region, userType);
  }

  private void givenValidKakaoTicket() {
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserIdHmac(eq("kakao"), any()))
        .willReturn(false);
    given(userRepository.existsByPhoneNumberHmac(any())).willReturn(false);
  }

  private User capturedUser() {
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    then(userRepository).should().save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("정상 요청이면 회원을 생성하고 쿠키를 발급하며 role을 반환한다")
  void register_valid_createsUserAndIssuesTokens() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserIdHmac(eq("kakao"), any()))
        .willReturn(false);
    given(userRepository.existsByPhoneNumberHmac(any())).willReturn(false);

    // When
    RegisterResponse result = authRegisterService.register(response, request("insured_person"));

    // Then
    assertThat(result.nickname()).isEqualTo("홍길동");
    assertThat(result.role()).isEqualTo(Role.USER.name());
    then(userRepository).should().save(any(User.class));
    then(socialAccountRepository).should().save(any(SocialAccount.class));
    then(authTokenService).should().issueTokens(any(HttpServletResponse.class), any(), anyString());
  }

  @Test
  @DisplayName("요청의 name과 region을 그대로 users에 저장한다")
  void register_valid_savesNameAndRegion() {
    // Given
    givenValidKakaoTicket();

    // When
    authRegisterService.register(response, request("insured_person", "서울·경기"));

    // Then
    User saved = capturedUser();
    assertThat(saved.getNickname()).isEqualTo("홍길동");
    assertThat(saved.getRegion()).containsExactly("서울", "경기");
  }

  @ParameterizedTest(name = "region=\"{0}\"")
  @NullSource
  @ValueSource(strings = {"", "   ", "·"})
  @DisplayName("region이 없거나 공백·구분자뿐이면 빈 배열이 아니라 null로 저장한다")
  void register_blankRegion_savesNull(@Nullable String region) {
    // Given
    givenValidKakaoTicket();

    // When
    authRegisterService.register(response, request("insured_person", region));

    // Then
    assertThat(capturedUser().getRegion()).isNull();
  }

  @Test
  @DisplayName("단일 지역이면 원소 하나짜리 배열로 저장한다")
  void register_singleRegion_savesOneElement() {
    // Given
    givenValidKakaoTicket();

    // When
    authRegisterService.register(response, request("insured_person", "서울"));

    // Then
    assertThat(capturedUser().getRegion()).containsExactly("서울");
  }

  @Test
  @DisplayName("adjuster 유형이면 role을 UNCERTIFICATED_ADJUSTER로 매핑한다")
  void register_adjuster_mapsToUncertificatedAdjuster() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserIdHmac(eq("kakao"), any()))
        .willReturn(false);
    given(userRepository.existsByPhoneNumberHmac(any())).willReturn(false);

    // When
    RegisterResponse result = authRegisterService.register(response, request("adjuster"));

    // Then
    assertThat(result.role()).isEqualTo(Role.UNCERTIFICATED_ADJUSTER.name());
  }

  @Test
  @DisplayName("Apple 가입이면 스테이징된 refresh_token(암호문)을 SocialAccount에 옮겨 저장한다")
  void register_apple_movesStagedRefreshTokenToAccount() {
    // Given
    RegisterRequest appleRequest = new RegisterRequest(
        "apple", "ticket", "홍길동", LocalDate.of(1990, 1, 1), PHONE, "남", "서울", "insured_person");
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("apple", "apple-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserIdHmac(eq("apple"), any()))
        .willReturn(false);
    given(userRepository.existsByPhoneNumberHmac(any())).willReturn(false);
    given(appleRefreshStagingRepository.consume("apple", "apple-1"))
        .willReturn(Optional.of("enc-refresh"));

    // When
    authRegisterService.register(response, appleRequest);

    // Then
    ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
    then(socialAccountRepository).should().save(captor.capture());
    assertThat(captor.getValue().getRefreshToken()).isEqualTo("enc-refresh");
    assertThat(capturedUser().getRegion()).containsExactly("서울");
  }

  @Test
  @DisplayName("티켓 provider와 요청 provider가 다르면 INVALID_TOKEN을 던진다")
  void register_providerMismatch_throwsInvalidToken() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("naver", "naver-1"));

    // When & Then
    assertThatThrownBy(() -> authRegisterService.register(response, request("insured_person")))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_TOKEN);
    then(userRepository).should(never()).save(any(User.class));
  }

  @Test
  @DisplayName("이미 연결된 소셜 계정이면 DUPLICATE_RESOURCE를 던진다")
  void register_duplicateSocialAccount_throwsConflict() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserIdHmac(eq("kakao"), any()))
        .willReturn(true);

    // When & Then
    assertThatThrownBy(() -> authRegisterService.register(response, request("insured_person")))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    then(userRepository).should(never()).save(any(User.class));
  }

  @Test
  @DisplayName("이미 가입된 전화번호면 DUPLICATE_RESOURCE를 던진다")
  void register_duplicatePhoneNumber_throwsConflict() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserIdHmac(eq("kakao"), any()))
        .willReturn(false);
    given(userRepository.existsByPhoneNumberHmac(any())).willReturn(true);

    // When & Then
    assertThatThrownBy(() -> authRegisterService.register(response, request("insured_person")))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    then(authTokenService).should(never()).issueTokens(any(), any(), anyString());
  }
}
