package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  private HttpServletResponse response;

  private static final String PHONE = "010-1234-5678";

  private RegisterRequest request(String userType) {
    return new RegisterRequest(
        "kakao", "ticket", "홍길동", LocalDate.of(1990, 1, 1), PHONE, "남", userType);
  }

  @Test
  @DisplayName("정상 요청이면 회원을 생성하고 쿠키를 발급하며 role을 반환한다")
  void register_valid_createsUserAndIssuesTokens() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserId("kakao", "kakao-1"))
        .willReturn(false);
    given(userRepository.existsByPhoneNumber(PHONE)).willReturn(false);

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
  @DisplayName("adjuster 유형이면 role을 UNCERTIFICATED_ADJUSTER로 매핑한다")
  void register_adjuster_mapsToUncertificatedAdjuster() {
    // Given
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("kakao", "kakao-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserId("kakao", "kakao-1"))
        .willReturn(false);
    given(userRepository.existsByPhoneNumber(PHONE)).willReturn(false);

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
        "apple", "ticket", "홍길동", LocalDate.of(1990, 1, 1), PHONE, "남", "insured_person");
    given(signupTicketProvider.parse("ticket"))
        .willReturn(new SignupTicket("apple", "apple-1"));
    given(socialAccountRepository.existsByProviderAndProviderUserId("apple", "apple-1"))
        .willReturn(false);
    given(userRepository.existsByPhoneNumber(PHONE)).willReturn(false);
    given(appleRefreshStagingRepository.consume("apple", "apple-1"))
        .willReturn(Optional.of("enc-refresh"));

    // When
    authRegisterService.register(response, appleRequest);

    // Then
    ArgumentCaptor<SocialAccount> captor = ArgumentCaptor.forClass(SocialAccount.class);
    then(socialAccountRepository).should().save(captor.capture());
    assertThat(captor.getValue().getRefreshToken()).isEqualTo("enc-refresh");
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
    given(socialAccountRepository.existsByProviderAndProviderUserId("kakao", "kakao-1"))
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
    given(socialAccountRepository.existsByProviderAndProviderUserId("kakao", "kakao-1"))
        .willReturn(false);
    given(userRepository.existsByPhoneNumber(PHONE)).willReturn(true);

    // When & Then
    assertThatThrownBy(() -> authRegisterService.register(response, request("insured_person")))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    then(authTokenService).should(never()).issueTokens(any(), any(), anyString());
  }
}
