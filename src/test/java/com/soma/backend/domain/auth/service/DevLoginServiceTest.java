package com.soma.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletResponse;

import com.soma.backend.domain.auth.dto.DevLoginRequest;
import com.soma.backend.domain.auth.dto.DevLoginResponse;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("DevLoginService 단위 테스트")
class DevLoginServiceTest {

  @Mock
  private UserRepository userRepository;
  @Mock
  private JwtProvider jwtProvider;
  @Mock
  private CookieProvider cookieProvider;
  @InjectMocks
  private DevLoginService service;

  @Test
  @DisplayName("기존 유저가 없으면 USER로 생성하고 access_token 쿠키를 발급한다")
  void login_createsUserAndIssuesCookie() {
    given(userRepository.findByNickname("로컬테스트유저")).willReturn(Optional.empty());
    given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));
    given(jwtProvider.generateAccessToken(any(), any())).willReturn("test.access.token");
    given(cookieProvider.buildAccessTokenCookie("test.access.token"))
        .willReturn(ResponseCookie.from("access_token", "test.access.token").build());
    MockHttpServletResponse response = new MockHttpServletResponse();

    DevLoginResponse result = service.login(response, new DevLoginRequest(null));

    assertThat(result.role()).isEqualTo("USER");
    assertThat(result.nickname()).isEqualTo("로컬테스트유저");
    assertThat(response.getHeader("Set-Cookie")).contains("access_token");
    verify(userRepository).save(any(User.class));
  }

  @Test
  @DisplayName("기존 유저가 있으면 재사용하고 새로 생성하지 않는다")
  void login_reusesExistingUser() {
    User existing = User.create("길동", LocalDate.of(1990, 1, 1), "MALE", null, Role.USER, null);
    given(userRepository.findByNickname("길동")).willReturn(Optional.of(existing));
    given(jwtProvider.generateAccessToken(any(), any())).willReturn("t");
    given(cookieProvider.buildAccessTokenCookie(any()))
        .willReturn(ResponseCookie.from("access_token", "t").build());
    MockHttpServletResponse response = new MockHttpServletResponse();

    DevLoginResponse result = service.login(response, new DevLoginRequest("길동"));

    assertThat(result.nickname()).isEqualTo("길동");
    verify(userRepository, never()).save(any());
  }
}
