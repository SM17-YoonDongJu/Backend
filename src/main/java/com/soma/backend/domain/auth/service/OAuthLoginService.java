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
import com.soma.backend.global.security.crypto.AesGcmCipher;
import com.soma.backend.global.security.crypto.PiiAad;
import com.soma.backend.global.security.crypto.PiiHmac;
import com.soma.backend.infra.redis.AppleRefreshStagingRepository;

/**
 * OAuth 콜백 유스케이스. 인가코드를 프로필로 교환한 뒤 소셜 계정 존재 여부로 로그인/가입을 분기한다.
 *
 * <ul>
 *   <li>기존 회원: access·refresh 쿠키 발급 후 {@code isNewUser=false}</li>
 *   <li>신규 회원: 쿠키 없이 가입 티켓 발급 후 {@code isNewUser=true}</li>
 * </ul>
 *
 * <p>Apple 신규 유저는 콜백에서 확보한 refresh_token을 암호화해 스테이징(콜백→가입 갭 브릿지)해 두었다가
 * 가입 시 SocialAccount로 옮긴다(탈퇴 revoke용). 다른 provider는 refresh가 없어 스테이징하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OAuthLoginService {

  private static final PiiAad PROVIDER_USER_ID_AAD = PiiAad.ofColumn("social_accounts", "provider_user_id");

  private final OAuthClient oAuthClient;
  private final SocialAccountRepository socialAccountRepository;
  private final UserRepository userRepository;
  private final SignupTicketProvider signupTicketProvider;
  private final AuthTokenService authTokenService;
  private final AesGcmCipher aesGcmCipher;
  private final AppleRefreshStagingRepository appleRefreshStagingRepository;
  private final PiiHmac piiHmac;

  public OAuthCallbackResponse handleCallback(
      HttpServletResponse response, String provider, String code, String state, String redirectUri) {

    // 외부 OAuth 프로필 조회는 트랜잭션 밖에서 수행한다(HTTP 지연 시 DB 커넥션 점유·풀 고갈 방지).
    OAuthProfile profile = oAuthClient.fetchProfile(provider, code, state, redirectUri);

    byte[] providerUserIdHmac = piiHmac.hmac(profile.providerUserId(), PROVIDER_USER_ID_AAD);
    return socialAccountRepository
        .findByProviderAndProviderUserIdHmac(profile.provider(), providerUserIdHmac)
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
    if (profile.providerRefreshToken() != null) {
      // Apple만 non-null. 평문을 즉시 암호화해 스테이징하고, 가입 시점에 SocialAccount로 옮긴다.
      // AAD는 행 자연키(provider, providerUserId) 바인딩 — 다른 계정 행에 잘못 들어가면 복호화가 실패한다.
      appleRefreshStagingRepository.stage(
          profile.provider(),
          profile.providerUserId(),
          aesGcmCipher.encrypt(
              profile.providerRefreshToken(),
              AesGcmCipher.appleRefreshTokenAad(profile.provider(), profile.providerUserId())));
    }
    String ticket = signupTicketProvider.issue(profile.provider(), profile.providerUserId());
    return OAuthCallbackResponse.newUser(ticket);
  }
}
