package com.soma.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.entity.SocialAccount;
import com.soma.backend.domain.auth.repository.SocialAccountRepository;
import com.soma.backend.domain.user.dto.UserMeResponse;
import com.soma.backend.domain.user.dto.UserUpdateRequest;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.entity.UserStatus;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.infra.redis.TokenBlacklistRepository;
import com.soma.backend.infra.redis.WithdrawalLedgerRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

  @InjectMocks
  private UserService userService;

  @Mock
  private UserRepository userRepository;

  @Mock
  private SocialAccountRepository socialAccountRepository;

  @Mock
  private AuthTokenService authTokenService;

  @Mock
  private TokenBlacklistRepository tokenBlacklistRepository;

  @Mock
  private WithdrawalLedgerRepository withdrawalLedgerRepository;

  @Mock
  private HttpServletResponse response;

  private User activeUser() {
    return User.create("홍길동", LocalDate.of(1990, 1, 1), "남", "010-1234-5678", Role.USER);
  }

  @Test
  @DisplayName("getMe-존재하는 활성 회원이면 정보를 반환한다")
  void getMe_active_returnsInfo() {
    // Given
    UUID userId = UUID.randomUUID();
    given(userRepository.findById(userId)).willReturn(Optional.of(activeUser()));

    // When
    UserMeResponse result = userService.getMe(userId);

    // Then
    assertThat(result.nickname()).isEqualTo("홍길동");
    assertThat(result.phoneNumber()).isEqualTo("010-1234-5678");
    assertThat(result.role()).isEqualTo("USER");
  }

  @Test
  @DisplayName("getMe-회원이 없으면 USER_NOT_FOUND를 던진다")
  void getMe_notFound_throws() {
    // Given
    UUID userId = UUID.randomUUID();
    given(userRepository.findById(userId)).willReturn(Optional.empty());

    // When & Then
    assertThatThrownBy(() -> userService.getMe(userId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
  }

  @Test
  @DisplayName("getMe-이미 탈퇴한 회원이면 USER_NOT_FOUND를 던진다")
  void getMe_withdrawn_throws() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = activeUser();
    user.withdraw();
    given(userRepository.findById(userId)).willReturn(Optional.of(user));

    // When & Then
    assertThatThrownBy(() -> userService.getMe(userId))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
  }

  @Test
  @DisplayName("updateMe-수정할 필드가 없으면 INVALID_REQUEST를 던진다")
  void updateMe_noField_throws() {
    // Given
    UUID userId = UUID.randomUUID();
    UserUpdateRequest request = new UserUpdateRequest(null, null, null);

    // When & Then
    assertThatThrownBy(() -> userService.updateMe(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_REQUEST);
    then(userRepository).should(never()).findById(userId);
  }

  @Test
  @DisplayName("updateMe-지역·프로필사진을 부분 수정하고 전화번호는 유지한다")
  void updateMe_partial_updatesFields() {
    // Given
    UUID userId = UUID.randomUUID();
    given(userRepository.findById(userId)).willReturn(Optional.of(activeUser()));
    UserUpdateRequest request = new UserUpdateRequest(null, "서울", "https://img/new.png");

    // When
    UserMeResponse result = userService.updateMe(userId, request);

    // Then
    assertThat(result.region()).isEqualTo("서울");
    assertThat(result.avatarUrl()).isEqualTo("https://img/new.png");
    assertThat(result.phoneNumber()).isEqualTo("010-1234-5678");
    then(userRepository).should(never()).existsByPhoneNumber("010-1234-5678");
  }

  @Test
  @DisplayName("updateMe-다른 회원이 쓰는 번호로 바꾸면 DUPLICATE_RESOURCE를 던진다")
  void updateMe_duplicatePhone_throws() {
    // Given
    UUID userId = UUID.randomUUID();
    given(userRepository.findById(userId)).willReturn(Optional.of(activeUser()));
    given(userRepository.existsByPhoneNumber("010-9999-8888")).willReturn(true);
    UserUpdateRequest request = new UserUpdateRequest("010-9999-8888", null, null);

    // When & Then
    assertThatThrownBy(() -> userService.updateMe(userId, request))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
  }

  @Test
  @DisplayName("withdraw-소셜을 원장에 기록하고 계정을 익명화하며 세션을 무효화한다")
  void withdraw_anonymizesAndInvalidates() {
    // Given
    UUID userId = UUID.randomUUID();
    User user = activeUser();
    SocialAccount social = mock(SocialAccount.class);
    given(social.getProvider()).willReturn("kakao");
    given(social.getProviderUserId()).willReturn("kakao-1");
    given(userRepository.findById(userId)).willReturn(Optional.of(user));
    given(socialAccountRepository.findByUserId(userId)).willReturn(List.of(social));

    // When
    userService.withdraw(userId, response);

    // Then
    assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    assertThat(user.getPhoneNumber()).isNull();
    then(withdrawalLedgerRepository).should().record("kakao", "kakao-1");
    then(socialAccountRepository).should().deleteByUserId(userId);
    then(authTokenService).should().clearTokens(response, userId);
    then(tokenBlacklistRepository).should().blacklist(userId);
  }
}
