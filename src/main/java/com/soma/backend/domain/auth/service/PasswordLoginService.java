package com.soma.backend.domain.auth.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;

import com.soma.backend.domain.auth.dto.PasswordAuthResponse;
import com.soma.backend.domain.auth.dto.PasswordLoginRequest;
import com.soma.backend.domain.auth.entity.PasswordCredential;
import com.soma.backend.domain.auth.repository.PasswordCredentialRepository;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenManager;

/**
 * 아이디·비밀번호 로그인 유스케이스(이슈 #389). 자격증명을 대조하고 access·refresh 쿠키를 발급한다.
 *
 * <p>실패 사유(아이디 없음·비밀번호 불일치·탈퇴 계정)를 구분하지 않고 모두 {@code LOGIN_FAILED}(401)로
 * 응답한다 — 응답이 갈리면 계정 존재 여부를 알아내는 열거(enumeration) 수단이 된다.
 *
 * <p>아이디·비밀번호는 로그에 남기지 않는다(PII 로깅 규칙). 실패 원인은 응답 코드로만 드러난다.
 *
 * <p>{@code app.password-login.enabled=false}면 빈으로 등록되지 않는다(기본값은 등록).
 */
@Service
@ConditionalOnProperty(
    prefix = "app.password-login", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PasswordLoginService {

  private final PasswordCredentialRepository passwordCredentialRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthTokenManager authTokenManager;

  /**
   * 존재하지 않는 아이디로 들어온 요청에도 해시 대조를 한 번 수행하기 위한 버리는 해시.
   * 실제 비밀번호가 아니라 임의값을 해싱한 것이라 이 값으로는 아무 계정도 열 수 없다.
   */
  private final String absentCredentialHash;

  /**
   * 더미 해시를 상수로 박지 않고 주입된 인코더로 기동 시 한 번 만든다 — 그래야 알고리즘·강도(cost)가
   * 실제 저장 해시와 항상 같아서 "아이디 없음" 경로의 대조 비용이 "비밀번호 불일치" 경로와 같아진다
   * (하드코딩하면 인코더 설정을 바꿀 때 조용히 어긋난다).
   */
  public PasswordLoginService(
      PasswordCredentialRepository passwordCredentialRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      AuthTokenManager authTokenManager) {
    this.passwordCredentialRepository = passwordCredentialRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.authTokenManager = authTokenManager;
    this.absentCredentialHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * 자격증명을 대조하고 토큰 쿠키를 발급한다. 토큰에 싣는 role은 가입 시점 값이 아니라 DB의 현재
   * {@code users.role}이다 — 가입 후 사정사로 승격된 계정도 올바른 권한으로 로그인된다.
   *
   * <p><b>트랜잭션을 열지 않는다.</b> 단순 조회 두 건이라 묶을 원자성이 없고, 트랜잭션을 걸면 BCrypt
   * 대조(cost 10 = 수십 ms)가 끝날 때까지 DB 커넥션을 쥐고 있게 된다. 로그인은 동시 요청이 몰리는
   * 경로인데 풀은 15개(HikariCP)뿐이라 그만큼 다른 요청이 커넥션을 기다리고, 아이디가 없어 실패하는
   * 요청도 더미 해시 대조로 같은 비용을 쓰므로 더 그렇다. 트랜잭션이 없으면 리포지토리 호출마다
   * 커넥션을 잡고 즉시 반납하므로, 해시 대조 구간에는 커넥션을 점유하지 않는다.
   */
  public PasswordAuthResponse login(HttpServletResponse response, PasswordLoginRequest request) {
    String loginId = PasswordCredential.normalizeLoginId(request.loginId());
    Optional<PasswordCredential> found = passwordCredentialRepository.findByLoginId(loginId);
    if (found.isEmpty()) {
      // 아이디가 없을 때도 해시 대조를 한 번 수행해 응답 시간을 맞춘다. 바로 던지면 BCrypt 비용(수십 ms)이
      // 빠지면서 "존재하지 않는 아이디"가 응답 지연 차이로 드러난다(타이밍 오라클).
      passwordEncoder.matches(request.password(), absentCredentialHash);
      throw new BusinessException(ErrorCode.LOGIN_FAILED);
    }

    PasswordCredential credential = found.get();
    if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
      throw new BusinessException(ErrorCode.LOGIN_FAILED);
    }

    // 탈퇴 계정은 자격증명이 남아 있어도 로그인시키지 않는다. 회원이 아예 없는 경우(데이터 불일치)도
    // 같은 실패로 처리한다 — 내부 상태를 응답으로 구분해 주지 않는다.
    User user = userRepository.findById(credential.getUserId())
        .filter(candidate -> !candidate.isWithdrawn())
        .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

    authTokenManager.issueTokens(response, user.getId(), user.getRole().name());
    return new PasswordAuthResponse(user.getId(), user.getNickname(), user.getRole().name());
  }
}
