package com.soma.backend.domain.auth.service.provider.apple;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.soma.backend.domain.auth.service.provider.OAuthProfile;
import com.soma.backend.domain.auth.service.provider.OAuthProviderStrategy;
import com.soma.backend.domain.auth.service.provider.OAuthTokenExchanger;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * Apple(Web/Services ID) 전략. kakao/naver와 달리 user-info 엔드포인트가 없으므로 토큰 응답의
 * {@code id_token}(OIDC JWT)을 검증해 {@code sub}를 providerUserId로 정규화한다. 이메일은 수집하지 않는다.
 *
 * <p>{@code @Component}라 {@code OAuthClientRouter}가 {@link #provider()} 키({@code "apple"})로 자동 라우팅한다.
 */
@Component
public class AppleOAuthClient implements OAuthProviderStrategy {

  private static final String APPLE = "apple";
  private static final String ID_TOKEN = "id_token";

  private final AppleOAuthProperties properties;
  private final AppleClientSecretGenerator clientSecretGenerator;
  private final AppleIdTokenVerifier idTokenVerifier;
  private final OAuthTokenExchanger tokenExchanger;

  public AppleOAuthClient(
      AppleOAuthProperties properties,
      AppleClientSecretGenerator clientSecretGenerator,
      AppleIdTokenVerifier idTokenVerifier,
      OAuthTokenExchanger tokenExchanger) {
    this.properties = properties;
    this.clientSecretGenerator = clientSecretGenerator;
    this.idTokenVerifier = idTokenVerifier;
    this.tokenExchanger = tokenExchanger;
  }

  @Override
  public String provider() {
    return APPLE;
  }

  @Override
  public OAuthProfile fetchProfile(String code, String state, String redirectUri) {
    String redirect = tokenExchanger.resolveRedirectUri(properties.getRedirectUri(), redirectUri);
    String secret = clientSecretGenerator.currentSecret();
    Map<String, Object> token = tokenExchanger.exchangeToken(
        properties.getTokenUri(),
        properties.getClientId(),
        secret,
        code,
        state,
        redirect);
    Object idToken = token.get(ID_TOKEN);
    if (idToken == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    String subject = idTokenVerifier.verifyAndGetSubject(idToken.toString());
    return new OAuthProfile(APPLE, subject);
  }
}
