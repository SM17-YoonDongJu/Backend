package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthReissueService 단위 테스트")
class AuthReissueServiceTest {

  @InjectMocks
  private AuthReissueService authReissueService;

  @Mock
  private AuthTokenService authTokenService;

  @Mock
  private UserRepository userRepository;

  @Mock
  private HttpServletRequest request;

  @Mock
  private HttpServletResponse response;

  @Test
  @DisplayName("refresh 쿠키가 유효하면 최신 role로 새 토큰 쌍을 발급한다")
  void reissue_validRefresh_issuesTokens() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = mock(User.class);
    given(authTokenService.validateRefreshCookie(request)).willReturn(userId);
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(user.getRole()).willReturn(Role.CERTIFICATED_ADJUSTER);

    // When
    authReissueService.reissue(request, response);

    // Then
    then(authTokenService).should().issueTokens(response, userId, "CERTIFICATED_ADJUSTER");
  }

  @Test
  @DisplayName("검증된 userId의 회원이 없으면 USER_NOT_FOUND를 던진다")
  void reissue_userNotFound_throwsUserNotFound() {
    // Given
    UUID userId = UUID.randomUUID();
    given(authTokenService.validateRefreshCookie(request)).willReturn(userId);
    given(userRepository.findById(userId)).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> authReissueService.reissue(request, response))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
    then(authTokenService).should(never()).issueTokens(any(), any(), anyString());
  }
}
