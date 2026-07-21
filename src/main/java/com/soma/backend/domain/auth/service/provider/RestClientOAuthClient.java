package com.soma.backend.domain.auth.service.provider;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * {@link OAuthClient}의 실제 HTTP 구현. {@link ClientRegistrationRepository}에서 제공자별
 * token-uri/user-info-uri/client-id/secret/redirect-uri를 읽어 수동으로 토큰을 교환한다.
 *
 * <p>토큰 교환의 {@code redirect_uri}는 인가 요청과 정확히 일치해야 제공자가 토큰을 발급한다. 프론트가
 * 콜백에 {@code redirect_uri}를 넘기면 허용목록({@code app.oauth.allowed-redirect-origins}) 검증 후
 * 그 값을 그대로 사용하고(한 서버가 여러 프론트 오리진 지원), 없으면 {@code app.oauth.base-url}로
 * {@code {baseUrl}}을 치환한 설정값을 fallback으로 쓴다.
 */
@Component
public class RestClientOAuthClient implements OAuthClient {

  private static final String KAKAO = "kakao";
  private static final String NAVER = "naver";
  private static final String BASE_URL_PLACEHOLDER = "{baseUrl}";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final ClientRegistrationRepository clientRegistrationRepository;
  private final RestClient restClient;
  private final String baseUrl;
  private final List<String> allowedRedirectOrigins;

  public RestClientOAuthClient(
      ClientRegistrationRepository clientRegistrationRepository,
      @Value("${app.oauth.base-url:http://localhost:8080}") String baseUrl,
      @Value("${app.oauth.allowed-redirect-origins:http://localhost:8080,http://localhost:3000}")
          List<String> allowedRedirectOrigins) {
    this.clientRegistrationRepository = clientRegistrationRepository;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(CONNECT_TIMEOUT);
    factory.setReadTimeout(READ_TIMEOUT);
    this.restClient = RestClient.builder().requestFactory(factory).build();
    this.baseUrl = baseUrl;
    this.allowedRedirectOrigins = allowedRedirectOrigins;
  }

  @Override
  public OAuthProfile fetchProfile(String provider, String code, String state, String redirectUri) {
    ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(provider);
    if (registration == null) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
    }
    String accessToken = exchangeToken(registration, code, state, redirectUri);
    Map<String, Object> userInfo = fetchUserInfo(registration, accessToken);
    return parseProfile(provider, userInfo);
  }

  private String exchangeToken(
      ClientRegistration registration, String code, String state, String redirectUri) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", registration.getClientId());
    form.add("client_secret", registration.getClientSecret());
    form.add("code", code);
    form.add("redirect_uri", resolveRedirectUri(registration, redirectUri));
    // 네이버 토큰 교환은 state를 요구한다(인가 요청의 state와 일치해야 함). 카카오는 미사용이나
    // 여분 파라미터를 무시하므로, state가 있을 때만 실어 두 제공자 모두 안전하게 처리한다.
    if (state != null && !state.isBlank()) {
      form.add("state", state);
    }

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
    } catch (RestClientResponseException | ResourceAccessException ex) {
      // RestClientResponseException = HTTP 오류 응답, ResourceAccessException = 타임아웃·연결 실패·DNS 오류
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
    } catch (ResourceAccessException ex) {
      // 타임아웃·연결 실패·DNS 오류
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  /**
   * 토큰 교환에 쓸 redirect_uri를 결정한다. 프론트가 넘긴 값이 있으면 허용목록 검증 후 그대로 쓰고,
   * 없으면 설정된 {@code redirect-uri}의 {@code {baseUrl}}를 {@code app.oauth.base-url}로 치환한다.
   */
  private String resolveRedirectUri(ClientRegistration registration, String requestedRedirectUri) {
    if (requestedRedirectUri != null && !requestedRedirectUri.isBlank()) {
      if (!isAllowedRedirectUri(requestedRedirectUri)) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
      }
      return requestedRedirectUri;
    }
    String configured = registration.getRedirectUri();
    if (configured == null) {
      return null;
    }
    return configured.replace(BASE_URL_PLACEHOLDER, baseUrl);
  }

  private boolean isAllowedRedirectUri(String redirectUri) {
    try {
      URI uri = URI.create(redirectUri);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return false;
      }
      String origin = uri.getScheme() + "://" + uri.getHost()
          + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
      return allowedRedirectOrigins.contains(origin);
    } catch (IllegalArgumentException ex) {
      return false;
    }
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
    return new OAuthProfile(KAKAO, id.toString());
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
    return new OAuthProfile(NAVER, id.toString());
  }
}
