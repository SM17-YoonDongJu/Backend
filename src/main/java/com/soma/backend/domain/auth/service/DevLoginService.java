package com.soma.backend.domain.auth.service;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.DevLoginRequest;
import com.soma.backend.domain.auth.dto.DevLoginResponse;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.JwtProvider;

/**
 * 로컬 개발 전용 로그인 유스케이스. 소셜 OAuth를 거치지 않고 테스트 유저의 access_token 쿠키를 발급한다.
 * {@code app.dev-login.enabled=true}일 때만 빈으로 등록되며, 운영 노출 차단은 {@code DevLoginGuard}가 담당한다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.dev-login", name = "enabled", havingValue = "true")
public class DevLoginService {

  /** nickname 미지정 시 사용하는 기본 테스트 유저. */
  private static final String DEFAULT_NICKNAME = "로컬테스트유저";
  private static final LocalDate DEFAULT_BIRTH_DATE = LocalDate.of(1990, 1, 1);
  private static final String DEFAULT_GENDER = "MALE";

  private final UserRepository userRepository;
  private final JwtProvider jwtProvider;
  private final CookieProvider cookieProvider;

  /**
   * 테스트 유저를 조회하거나(없으면 USER 역할로 생성) access_token 쿠키를 발급한다. 권한 상승을 막기 위해
   * 역할은 요청으로 받지 않으며, 신규 유저는 항상 USER로 만든다.
   */
  @Transactional
  public DevLoginResponse login(HttpServletResponse response, DevLoginRequest request) {
    String nickname = resolveNickname(request);
    User user = userRepository.findByNickname(nickname)
        .orElseGet(() -> userRepository.save(
            User.create(nickname, DEFAULT_BIRTH_DATE, DEFAULT_GENDER, null, Role.USER)));

    String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getRole().name());
    response.addHeader(
        HttpHeaders.SET_COOKIE, cookieProvider.buildAccessTokenCookie(accessToken).toString());

    return new DevLoginResponse(user.getId(), user.getNickname(), user.getRole().name());
  }

  private String resolveNickname(DevLoginRequest request) {
    if (request == null || request.nickname() == null || request.nickname().isBlank()) {
      return DEFAULT_NICKNAME;
    }
    return request.nickname();
  }
}
