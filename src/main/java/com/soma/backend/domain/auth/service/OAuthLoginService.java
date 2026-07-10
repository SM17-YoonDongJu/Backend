package com.soma.backend.domain.auth.service;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.auth.dto.OAuthCallbackResponse;
import com.soma.backend.domain.auth.entity.SocialAccount;
import com.soma.backend.domain.auth.repository.SocialAccountRepository;
import com.soma.backend.domain.auth.service.provider.OAuthClient;
import com.soma.backend.domain.auth.service.provider.OAuthProfile;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.AuthTokenService;
import com.soma.backend.infra.redis.WithdrawalLedgerRepository;

/**
 * OAuth 콜백 유스케이스. 인가코드를 프로필로 교환한 뒤 소셜 계정 존재 여부로 로그인/가입을 분기한다.
 *
 * <ul>
 *   <li>기존 회원: access·refresh 쿠키 발급 후 {@code isNewUser=false}</li>
 *   <li>신규 회원: 쿠키 없이 가입 티켓 발급 후 {@code isNewUser=true}</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OAuthLoginService {

  private final OAuthClient oAuthClient;
  private final SocialAccountRepository socialAccountRepository;
  private final UserRepository userRepository;
  private final SignupTicketProvider signupTicketProvider;
  private final AuthTokenService authTokenService;
  private final WithdrawalLedgerRepository withdrawalLedgerRepository;

  public OAuthCallbackResponse handleCallback(
      HttpServletResponse response, String provider, String code, String state) {

    // 외부 OAuth 프로필 조회는 트랜잭션 밖에서 수행한다(HTTP 지연 시 DB 커넥션 점유·풀 고갈 방지).
    OAuthProfile profile = oAuthClient.fetchProfile(provider, code, state);

    return socialAccountRepository
        .findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
        .map(account -> loginExisting(response, account))
        .orElseGet(() -> issueSignupTicket(profile));
  }

  private OAuthCallbackResponse loginExisting(HttpServletResponse response, SocialAccount account) {
    User user = userRepository.findById(account.getUserId())
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    authTokenService.issueTokens(response, user.getId(), user.getRole().name());
    return OAuthCallbackResponse.existingUser(user.getId());
  }

  private OAuthCallbackResponse issueSignupTicket(OAuthProfile profile) {
    String ticket = signupTicketProvider.issue(profile.provider(), profile.providerUserId());
    boolean previouslyWithdrawn =
        withdrawalLedgerRepository.wasWithdrawn(profile.provider(), profile.providerUserId());
    return OAuthCallbackResponse.newUser(ticket, previouslyWithdrawn);
  }
}
