package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.dto.PasswordAuthResponse;
import com.soma.backend.domain.auth.dto.PasswordLoginRequest;
import com.soma.backend.domain.auth.entity.PasswordCredential;
import com.soma.backend.domain.auth.repository.PasswordCredentialRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenManager;

/**
 * 아이디·비밀번호 로그인 유스케이스 단위 테스트(이슈 #389).
 *
 * <p>토큰에 DB의 현재 role이 실리는지, 실패 사유 네 갈래가 모두 같은 {@code LOGIN_FAILED}로 수렴하는지
 * (계정 열거 차단), 아이디가 없을 때도 해시 대조를 한 번 수행하는지(타이밍 오라클 차단)를 고정한다.
 * 마지막 항목은 성능 최적화로 쉽게 되돌려지는 지점이라 호출 자체를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordLoginService 단위 테스트")
class PasswordLoginServiceTest {

  private static final String LOGIN_ID = "honggil";
  private static final String RAW_PASSWORD = "plainpassword1234";
  private static final String STORED_HASH = "$2a$10$storedhashforhonggil";
  private static final String ABSENT_HASH = "$2a$10$throwawayhashforabsentlogin";
  private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock
  private PasswordCredentialRepository passwordCredentialRepository;

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private AuthTokenManager authTokenManager;

  @Mock
  private HttpServletResponse response;

  private PasswordLoginService passwordLoginService;

  /**
   * 서비스를 직접 생성한다. 생성자가 "아이디 없음" 경로용 더미 해시를 만들려고 {@code encode}를 1회
   * 호출하므로, 반환값을 고정해 두고 그 값으로 대조가 일어났는지 테스트에서 확인한다
   * ({@code encode} 호출 횟수를 셀 때도 이 1회가 포함된다).
   */
  @BeforeEach
  void setUp() {
    given(passwordEncoder.encode(anyString())).willReturn(ABSENT_HASH);
    passwordLoginService = new PasswordLoginService(
        passwordCredentialRepository, userRepository, passwordEncoder, authTokenManager);
  }

  /** 아이디는 대소문자·공백을 정규화해 조회하므로 요청에는 일부러 다듬지 않은 값을 넣는다. */
  private PasswordLoginRequest request() {
    return new PasswordLoginRequest("  HongGil  ", RAW_PASSWORD);
  }

  private User user(Role role) {
    User user = User.create("홍길동", LocalDate.of(1990, 1, 1), "남", null, null, role, null);
    ReflectionTestUtils.setField(user, "id", USER_ID);
    return user;
  }

  private void givenCredentialFound() {
    given(passwordCredentialRepository.findByLoginId(LOGIN_ID))
        .willReturn(Optional.of(PasswordCredential.create(USER_ID, LOGIN_ID, STORED_HASH)));
  }

  /** 로그인 실패는 사유를 구분하지 않고 모두 같은 ErrorCode로 수렴해야 한다. */
  private void assertLoginFailed() {
    assertThatThrownBy(() -> passwordLoginService.login(response, request()))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.LOGIN_FAILED);
  }

  @Test
  @DisplayName("정상 로그인이면 가입 시점이 아니라 DB의 현재 role이 토큰과 응답에 실린다")
  void login_usesCurrentRoleFromDatabase() {
    // Given
    givenCredentialFound();
    given(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);
    given(userRepository.findById(USER_ID))
        .willReturn(Optional.of(user(Role.CERTIFICATED_ADJUSTER)));

    // When
    PasswordAuthResponse result = passwordLoginService.login(response, request());

    // Then
    assertThat(result.role()).isEqualTo(Role.CERTIFICATED_ADJUSTER.name());
    assertThat(result.userId()).isEqualTo(USER_ID);
    assertThat(result.nickname()).isEqualTo("홍길동");
    then(authTokenManager).should()
        .issueTokens(response, USER_ID, Role.CERTIFICATED_ADJUSTER.name());
  }

  @Test
  @DisplayName("아이디가 없으면 더미 해시로 대조를 한 번 수행한 뒤 LOGIN_FAILED를 던진다")
  void login_unknownLoginId_stillComparesHash() {
    // Given
    given(passwordCredentialRepository.findByLoginId(LOGIN_ID)).willReturn(Optional.empty());

    // When & Then
    assertLoginFailed();
    then(passwordEncoder).should().matches(RAW_PASSWORD, ABSENT_HASH);
    then(passwordEncoder).should(times(1)).encode(anyString());
    then(userRepository).shouldHaveNoInteractions();
    then(authTokenManager).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("비밀번호가 다르면 LOGIN_FAILED이고 토큰을 발급하지 않는다")
  void login_wrongPassword_fails() {
    // Given
    givenCredentialFound();
    given(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).willReturn(false);

    // When & Then
    assertLoginFailed();
    then(userRepository).shouldHaveNoInteractions();
    then(authTokenManager).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("탈퇴한 계정은 자격증명이 맞아도 LOGIN_FAILED이고 토큰을 발급하지 않는다")
  void login_withdrawnUser_fails() {
    // Given
    givenCredentialFound();
    given(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);
    User withdrawn = user(Role.USER);
    withdrawn.withdraw();
    given(userRepository.findById(USER_ID)).willReturn(Optional.of(withdrawn));

    // When & Then
    assertLoginFailed();
    then(authTokenManager).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("자격증명만 있고 회원 행이 없으면(데이터 불일치) 같은 LOGIN_FAILED로 처리한다")
  void login_missingUser_fails() {
    // Given
    givenCredentialFound();
    given(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);
    given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

    // When & Then
    assertLoginFailed();
    then(authTokenManager).shouldHaveNoInteractions();
  }
}
