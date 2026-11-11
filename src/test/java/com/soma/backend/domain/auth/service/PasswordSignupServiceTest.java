package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.dto.PasswordAuthResponse;
import com.soma.backend.domain.auth.dto.PasswordSignupRequest;
import com.soma.backend.domain.auth.entity.PasswordCredential;
import com.soma.backend.domain.auth.repository.PasswordCredentialRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.entity.UserStatus;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenManager;

/**
 * 아이디·비밀번호 회원가입 유스케이스 단위 테스트(이슈 #389).
 *
 * <p>평문 비밀번호가 저장 경로로 새지 않는지, 아이디 정규화가 중복검사와 저장에 같은 값으로 적용되는지,
 * 역할이 요청과 무관하게 USER로 고정되는지(권한 상승 차단), 중복 시 고아 회원이 남지 않는지를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordSignupService 단위 테스트")
class PasswordSignupServiceTest {

  private static final String RAW_PASSWORD = "plainpassword1234";
  private static final String HASHED_PASSWORD = "$2a$10$hashedvalueforplainpassword1234";
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @InjectMocks
  private PasswordSignupService passwordSignupService;

  @Mock
  private UserRepository userRepository;

  @Mock
  private PasswordCredentialRepository passwordCredentialRepository;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private AuthTokenManager authTokenManager;

  @Mock
  private HttpServletResponse response;

  private PasswordSignupRequest request(String loginId) {
    return new PasswordSignupRequest(loginId, RAW_PASSWORD, "홍길동");
  }

  /**
   * 해싱 스텁과 저장 스텁을 함께 깔아 둔다. JPA가 persist 시 채우는 PK를 모사해 저장된 엔티티에 id를
   * 심어 주는데, 서비스가 {@code save} 반환값의 {@code getId()}로 토큰을 발급하기 때문이다.
   */
  private void givenEncoderAndSaveStubbed() {
    given(passwordEncoder.encode(RAW_PASSWORD)).willReturn(HASHED_PASSWORD);
    given(userRepository.save(any(User.class))).willAnswer(invocation -> {
      User user = invocation.getArgument(0);
      ReflectionTestUtils.setField(user, "id", USER_ID);
      return user;
    });
  }

  /** 저장소로 넘어간 User를 캡처한다(엔티티 팩터리 결과 검증용). */
  private User capturedUser() {
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    then(userRepository).should().save(captor.capture());
    return captor.getValue();
  }

  /** 저장소로 넘어간 자격증명을 캡처한다(해시·정규화 검증용). */
  private PasswordCredential capturedCredential() {
    ArgumentCaptor<PasswordCredential> captor = ArgumentCaptor.forClass(PasswordCredential.class);
    then(passwordCredentialRepository).should().save(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("비밀번호는 해시로 저장되고 평문은 자격증명에 남지 않는다")
  void signup_storesHashNotRawPassword() {
    // Given
    givenEncoderAndSaveStubbed();

    // When
    passwordSignupService.signup(response, request("honggil"));

    // Then
    PasswordCredential saved = capturedCredential();
    assertThat(saved.getPasswordHash()).isEqualTo(HASHED_PASSWORD);
    assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
    assertThat(saved.getUserId()).isEqualTo(USER_ID);
  }

  @Test
  @DisplayName("아이디의 앞뒤 공백·대문자를 정규화해 중복검사와 저장에 같은 값을 쓴다")
  void signup_normalizesLoginId() {
    // Given
    givenEncoderAndSaveStubbed();

    // When
    passwordSignupService.signup(response, request("  HongGil  "));

    // Then
    then(passwordCredentialRepository).should().existsByLoginId("honggil");
    assertThat(capturedCredential().getLoginId()).isEqualTo("honggil");
  }

  @Test
  @DisplayName("요청으로 역할을 받지 않으므로 생성 role과 토큰 role이 항상 USER로 고정된다")
  void signup_alwaysUsesUserRole() {
    // Given
    givenEncoderAndSaveStubbed();

    // When
    PasswordAuthResponse result = passwordSignupService.signup(response, request("honggil"));

    // Then
    assertThat(result.role()).isEqualTo(Role.USER.name());
    assertThat(result.userId()).isEqualTo(USER_ID);
    assertThat(result.nickname()).isEqualTo("홍길동");
    assertThat(capturedUser().getRole()).isEqualTo(Role.USER);
    then(authTokenManager).should().issueTokens(response, USER_ID, Role.USER.name());
  }

  @Test
  @DisplayName("아이디가 이미 있으면 DUPLICATE_RESOURCE이고 회원·자격증명을 하나도 만들지 않는다")
  void signup_duplicateLoginId_createsNothing() {
    // Given
    given(passwordCredentialRepository.existsByLoginId("honggil")).willReturn(true);

    // When & Then
    assertThatThrownBy(() -> passwordSignupService.signup(response, request("HongGil")))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

    then(userRepository).should(never()).save(any(User.class));
    then(passwordCredentialRepository).should(never()).save(any(PasswordCredential.class));
    then(passwordEncoder).shouldHaveNoInteractions();
    then(authTokenManager).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("72바이트를 넘는 비밀번호는 해싱 전에 400으로 거른다(@Size는 문자 수만 세므로 통과한다)")
  void signup_passwordOver72Bytes_rejectedBeforeHashing() {
    // Given — 한글 25자 = UTF-8 75바이트. 문자 수는 72 이하라 @Size(max = 72)를 통과한다.
    String koreanPassword = "가".repeat(25);
    assertThat(koreanPassword.length()).isLessThanOrEqualTo(72);
    assertThat(koreanPassword.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(72);

    // When & Then
    assertThatThrownBy(() -> passwordSignupService.signup(
        response, new PasswordSignupRequest("honggil", koreanPassword, "홍길동")))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.BAD_REQUEST);

    then(passwordEncoder).shouldHaveNoInteractions();
    then(userRepository).shouldHaveNoInteractions();
    then(passwordCredentialRepository).shouldHaveNoInteractions();
    then(authTokenManager).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("BCrypt 인코더는 72바이트 초과를 자르지 않고 예외로 거부한다(위 가드가 필요한 근거)")
  void bcryptEncoder_rejectsOver72Bytes() {
    // Given — 실제 인코더로 전제를 고정한다. 가드를 지우면 이 입력이 500이 된다.
    PasswordEncoder realEncoder = new BCryptPasswordEncoder();

    // When & Then
    assertThatThrownBy(() -> realEncoder.encode("가".repeat(25)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("72 bytes");
  }

  @Test
  @DisplayName("프로필을 받지 않는 가입이라 생년월일·성별은 미상 센티넬, 전화번호·지역은 null로 저장된다")
  void signup_createsUserWithoutProfile() {
    // Given
    givenEncoderAndSaveStubbed();

    // When
    passwordSignupService.signup(response, request("honggil"));

    // Then
    User saved = capturedUser();
    assertThat(saved.getBirthDate()).isEqualTo(LocalDate.of(1900, 1, 1));
    assertThat(saved.getGender()).isEmpty();
    assertThat(saved.getPhoneNumber()).isNull();
    assertThat(saved.getPhoneNumberHmac()).isNull();
    assertThat(saved.getRegion()).isNull();
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(saved.isWithdrawn()).isFalse();
  }
}
