package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.dto.OAuthCallbackResponse;
import com.soma.backend.domain.auth.entity.SocialAccount;
import com.soma.backend.domain.auth.repository.SocialAccountRepository;
import com.soma.backend.domain.auth.service.provider.OAuthClient;
import com.soma.backend.domain.auth.service.provider.OAuthProfile;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthLoginService 단위 테스트")
class OAuthLoginServiceTest {

  @InjectMocks
  private OAuthLoginService oAuthLoginService;

  @Mock
  private OAuthClient oAuthClient;

  @Mock
  private SocialAccountRepository socialAccountRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private SignupTicketProvider signupTicketProvider;

  @Mock
  private AuthTokenService authTokenService;

  @Mock
  private HttpServletResponse response;

  @Test
  @DisplayName("기존 회원이면 쿠키를 발급하고 isNewUser=false를 반환한다")
  void handleCallback_existingUser_issuesTokens() {
    // Given
    UUID userId = UUID.randomUUID();
    OAuthProfile profile = new OAuthProfile("kakao", "kakao-1");
    SocialAccount account = mock(SocialAccount.class);
    User user = mock(User.class);
    given(oAuthClient.fetchProfile("kakao", "code", "state", null)).willReturn(profile);
    given(socialAccountRepository.findByProviderAndProviderUserId("kakao", "kakao-1"))
        .willReturn(Optional.of(account));
    given(account.getUserId()).willReturn(userId);
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(user.getId()).willReturn(userId);
    given(user.getRole()).willReturn(Role.USER);

    // When
    OAuthCallbackResponse result = oAuthLoginService.handleCallback(response, "kakao", "code", "state", null);

    // Then
    assertThat(result.isNewUser()).isFalse();
    assertThat(result.userId()).isEqualTo(userId);
    assertThat(result.signupTicket()).isNull();
    then(authTokenService).should().issueTokens(response, userId, "USER");
    then(signupTicketProvider).should(never()).issue(anyString(), anyString());
  }

  @Test
  @DisplayName("신규 회원이면 쿠키 없이 가입 티켓을 반환하고 isNewUser=true를 반환한다")
  void handleCallback_newUser_returnsSignupTicket() {
    // Given
    OAuthProfile profile = new OAuthProfile("naver", "naver-9");
    given(oAuthClient.fetchProfile("naver", "code", null, null)).willReturn(profile);
    given(socialAccountRepository.findByProviderAndProviderUserId("naver", "naver-9"))
        .willReturn(Optional.empty());
    given(signupTicketProvider.issue("naver", "naver-9")).willReturn("ticket-jwt");

    // When
    OAuthCallbackResponse result = oAuthLoginService.handleCallback(response, "naver", "code", null, null);

    // Then
    assertThat(result.isNewUser()).isTrue();
    assertThat(result.signupTicket()).isEqualTo("ticket-jwt");
    assertThat(result.userId()).isNull();
    then(authTokenService).should(never()).issueTokens(any(), any(), anyString());
  }

  @Test
  @DisplayName("소셜 계정은 있으나 연결된 회원이 없으면 USER_NOT_FOUND를 던진다")
  void handleCallback_socialWithoutUser_throwsUserNotFound() {
    // Given
    UUID userId = UUID.randomUUID();
    OAuthProfile profile = new OAuthProfile("kakao", "kakao-2");
    SocialAccount account = mock(SocialAccount.class);
    given(oAuthClient.fetchProfile("kakao", "code", "state", null)).willReturn(profile);
    given(socialAccountRepository.findByProviderAndProviderUserId("kakao", "kakao-2"))
        .willReturn(Optional.of(account));
    given(account.getUserId()).willReturn(userId);
    given(userRepository.findById(userId)).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> oAuthLoginService.handleCallback(response, "kakao", "code", "state", null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
    then(authTokenService).should(never()).issueTokens(any(), eq(userId), anyString());
  }
}
