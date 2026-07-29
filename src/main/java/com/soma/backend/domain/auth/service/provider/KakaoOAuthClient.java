package com.soma.backend.domain.auth.service.provider;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 카카오 전략. {@link ClientRegistrationRepository}에서 카카오 설정을 읽어 {@link OAuthTokenExchanger}로
 * 토큰을 교환하고 user-info의 {@code id}를 프로필로 정규화한다.
 */
@Component
public class KakaoOAuthClient implements OAuthProviderStrategy {

  private static final String KAKAO = "kakao";

  private final ClientRegistrationRepository clientRegistrationRepository;
  private final OAuthTokenExchanger tokenExchanger;

  public KakaoOAuthClient(
      ClientRegistrationRepository clientRegistrationRepository, OAuthTokenExchanger tokenExchanger) {
    this.clientRegistrationRepository = clientRegistrationRepository;
    this.tokenExchanger = tokenExchanger;
  }

  @Override
  public String provider() {
    return KAKAO;
  }

  @Override
  public OAuthProfile fetchProfile(String code, String state, String redirectUri) {
    ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(KAKAO);
    if (registration == null) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
    }
    String redirect = tokenExchanger.resolveRedirectUri(registration.getRedirectUri(), redirectUri);
    Map<String, Object> token = tokenExchanger.exchangeToken(
        registration.getProviderDetails().getTokenUri(),
        registration.getClientId(),
        registration.getClientSecret(),
        code,
        state,
        redirect);
    Object accessToken = token.get("access_token");
    if (accessToken == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    Map<String, Object> userInfo = tokenExchanger.fetchUserInfo(
        registration.getProviderDetails().getUserInfoEndpoint().getUri(), accessToken.toString());
    Object id = userInfo.get("id");
    if (id == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    return new OAuthProfile(KAKAO, id.toString());
  }
}
