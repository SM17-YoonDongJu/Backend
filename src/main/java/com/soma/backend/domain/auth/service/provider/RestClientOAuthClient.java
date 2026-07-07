package com.soma.backend.domain.auth.service.provider;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * {@link OAuthClient}의 실제 HTTP 구현. {@link ClientRegistrationRepository}에서 제공자별
 * token-uri/user-info-uri/client-id/secret/redirect-uri를 읽어 수동으로 토큰을 교환한다.
 *
 * <p>{@code redirect-uri}의 {@code {baseUrl}} 플레이스홀더는 {@code app.oauth.base-url}로 치환한다.
 * 프론트가 인가 요청에 사용한 redirect_uri와 정확히 일치해야 제공자가 토큰을 발급한다.
 */
@Component
public class RestClientOAuthClient implements OAuthClient {

  private static final String KAKAO = "kakao";
  private static final String NAVER = "naver";
  private static final String BASE_URL_PLACEHOLDER = "{baseUrl}";

  private final ClientRegistrationRepository clientRegistrationRepository;
  private final RestClient restClient;
  private final String baseUrl;

  public RestClientOAuthClient(
      ClientRegistrationRepository clientRegistrationRepository,
      @Value("${app.oauth.base-url:http://localhost:8080}") String baseUrl) {
    this.clientRegistrationRepository = clientRegistrationRepository;
    this.restClient = RestClient.create();
    this.baseUrl = baseUrl;
  }

  @Override
  public OAuthProfile fetchProfile(String provider, String code, String state) {
    ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(provider);
    if (registration == null) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
    }
    String accessToken = exchangeToken(registration, code);
    Map<String, Object> userInfo = fetchUserInfo(registration, accessToken);
    return parseProfile(provider, userInfo);
  }

  private String exchangeToken(ClientRegistration registration, String code) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", registration.getClientId());
    form.add("client_secret", registration.getClientSecret());
    form.add("code", code);
    form.add("redirect_uri", resolveRedirectUri(registration));

    Map<String, Object> tokenResponse = post(registration.getProviderDetails().getTokenUri(), form);
    Object accessToken = tokenResponse.get("access_token");
    if (accessToken == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    return accessToken.toString();
  }

  private Map<String, Object> fetchUserInfo(ClientRegistration registration, String accessToken) {
    String userInfoUri = registration.getProviderDetails().getUserInfoEndpoint().getUri();
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = restClient.get()
          .uri(userInfoUri)
          .header("Authorization", "Bearer " + accessToken)
          .retrieve()
          .body(Map.class);
      if (body == null) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
      }
      return body;
    } catch (RestClientResponseException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  private Map<String, Object> post(String uri, MultiValueMap<String, String> form) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = restClient.post()
          .uri(uri)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(Map.class);
      if (body == null) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
      }
      return body;
    } catch (RestClientResponseException ex) {
      // 4xx = 유효하지 않은 인가코드 등 요청 오류, 그 외는 외부 연동 오류
      if (ex.getStatusCode().is4xxClientError()) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
      }
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  private String resolveRedirectUri(ClientRegistration registration) {
    String redirectUri = registration.getRedirectUri();
    if (redirectUri == null) {
      return null;
    }
    return redirectUri.replace(BASE_URL_PLACEHOLDER, baseUrl);
  }

  private OAuthProfile parseProfile(String provider, Map<String, Object> userInfo) {
    if (KAKAO.equalsIgnoreCase(provider)) {
      return parseKakao(userInfo);
    }
    if (NAVER.equalsIgnoreCase(provider)) {
      return parseNaver(userInfo);
    }
    throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
  }

  private OAuthProfile parseKakao(Map<String, Object> userInfo) {
    Object id = userInfo.get("id");
    if (id == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    String email = null;
    String nickname = null;
    Object accountObj = userInfo.get("kakao_account");
    if (accountObj instanceof Map<?, ?> account) {
      Object emailObj = account.get("email");
      email = emailObj == null ? null : emailObj.toString();
      Object profileObj = account.get("profile");
      if (profileObj instanceof Map<?, ?> profile) {
        Object nicknameObj = profile.get("nickname");
        nickname = nicknameObj == null ? null : nicknameObj.toString();
      }
    }
    return new OAuthProfile(KAKAO, id.toString(), email, nickname);
  }

  private OAuthProfile parseNaver(Map<String, Object> userInfo) {
    Object responseObj = userInfo.get("response");
    if (!(responseObj instanceof Map<?, ?> response)) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    Object id = response.get("id");
    if (id == null) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    Object emailObj = response.get("email");
    Object nicknameObj = response.get("nickname");
    String email = emailObj == null ? null : emailObj.toString();
    String nickname = nicknameObj == null ? null : nicknameObj.toString();
    return new OAuthProfile(NAVER, id.toString(), email, nickname);
  }
}
