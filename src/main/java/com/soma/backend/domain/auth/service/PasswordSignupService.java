package com.soma.backend.domain.auth.service;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.PasswordAuthResponse;
import com.soma.backend.domain.auth.dto.PasswordSignupRequest;
import com.soma.backend.domain.auth.entity.PasswordCredential;
import com.soma.backend.domain.auth.repository.PasswordCredentialRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenManager;

/**
 * 아이디·비밀번호 회원가입 유스케이스(이슈 #389). users + password_credentials를 한 트랜잭션으로 만든 뒤
 * access·refresh 쿠키를 발급한다.
 *
 * <p>한 트랜잭션으로 묶는 이유는 회원만 있고 자격증명이 없는(또는 그 반대의) 반쪽 상태를 만들지 않기
 * 위해서다. 아이디 중복은 {@code existsByLoginId}로 선검사하지만 그것은 사용자 메시지용이고, 동시 가입
 * 경쟁의 최종 방어선은 {@code login_id} UNIQUE 제약이다 — 커밋 시점에 터지는
 * {@code DataIntegrityViolationException}은 {@code GlobalExceptionHandler}가 409로 바꿔주므로 여기서
 * 따로 감싸지 않는다.
 *
 * <p>{@code app.password-login.enabled=false}면 빈으로 등록되지 않는다(기본값은 등록).
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.password-login", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PasswordSignupService {

  /**
   * 해싱에 넘길 수 있는 비밀번호의 최대 바이트 수. BCrypt는 키를 72바이트까지만 쓰는데, Spring Security 7의
   * {@code BCrypt.hashpw}는 그 이상을 받으면 조용히 자르지 않고 {@code IllegalArgumentException}을 던진다
   * (검증 쪽 {@code checkpw}는 던지지 않고 자른다 — 그래서 가입만 막으면 된다).
   *
   * <p>DTO의 {@code @Size(max = 72)}는 <b>문자 수</b>를 세므로 이 한도를 대신하지 못한다. 한글은 UTF-8에서
   * 글자당 3바이트라 25자(75바이트)면 검증을 통과한 뒤 인코더가 터져 500이 된다. 그 입력을 400으로 돌려주기
   * 위해 해싱 전에 바이트 길이를 직접 본다.
   */
  private static final int MAX_PASSWORD_BYTES = 72;

  private final UserRepository userRepository;
  private final PasswordCredentialRepository passwordCredentialRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthTokenManager authTokenManager;

  /**
   * 회원과 자격증명을 만들고 토큰 쿠키를 발급한다. 역할은 요청으로 받지 않고 항상 {@code USER}로 고정한다
   * (요청 값을 신뢰하면 가입만으로 ADMIN·사정사 권한을 가져가는 권한 상승 경로가 열린다).
   *
   * <p>비밀번호는 즉시 해싱해 엔티티에 넘기므로 평문이 엔티티·DB·로그에 남지 않는다. 해싱 전에
   * {@link #MAX_PASSWORD_BYTES}를 확인해 인코더가 던지는 {@code IllegalArgumentException}(→ 500)을
   * 400으로 앞당긴다.
   */
  @Transactional
  public PasswordAuthResponse signup(HttpServletResponse response, PasswordSignupRequest request) {
    if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new BusinessException(ErrorCode.BAD_REQUEST);
    }

    String loginId = PasswordCredential.normalizeLoginId(request.loginId());
    if (passwordCredentialRepository.existsByLoginId(loginId)) {
      throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    User user = userRepository.save(User.createWithoutProfile(request.name(), Role.USER));
    passwordCredentialRepository.save(PasswordCredential.create(
        user.getId(), loginId, passwordEncoder.encode(request.password())));

    authTokenManager.issueTokens(response, user.getId(), Role.USER.name());
    return new PasswordAuthResponse(user.getId(), user.getNickname(), Role.USER.name());
  }
}
