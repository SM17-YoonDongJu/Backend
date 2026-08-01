package com.soma.backend.domain.auth.service.provider;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 네이버 전략. 카카오와 동일 뼈대이나 user-info가 {@code response} 하위에 중첩되므로
 * {@code response.id}를 꺼내 프로필로 정규화한다.
 */
@Component
public class NaverOAuthClient implements OAuthProviderStrategy {

  private static final String NAVER = "naver";

  private final ClientRegistrationRepository clientRegistrationRepository;
  private final OAuthTokenExchanger tokenExchanger;

  public NaverOAuthClient(
      ClientRegistrationRepository clientRegistrationRepository, OAuthTokenExchanger tokenExchanger) {
    this.clientRegistrationRepository = clientRegistrationRepository;
    this.tokenExchanger = tokenExchanger;
  }

  @Override
  public String provider() {
    return NAVER;
  }

  @Override
  public OAuthProfile fetchProfile(String code, String state, String redirectUri) {
    ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(NAVER);
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
    Object responseObj = userInfo.get("response");
    if (!(responseObj instanceof Map<?, ?> response)) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    Object id = response.get("id");
    if (id == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    return new OAuthProfile(NAVER, id.toString());
  }
}
