package com.soma.backend.domain.auth.service.provider;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 소셜 제공자 공용 HTTP 코드교환기. 각 {@link OAuthProviderStrategy}가 조회한 제공자 설정을 받아
 * form-POST 토큰 교환·user-info 조회·redirect_uri 허용목록 검증을 수행한다.
 *
 * <p>토큰 교환의 {@code redirect_uri}는 인가 요청과 정확히 일치해야 제공자가 토큰을 발급한다. 프론트가
 * 콜백에 {@code redirect_uri}를 넘기면 허용목록({@code app.oauth.allowed-redirect-origins}) 검증 후
 * 그 값을 그대로 사용하고(한 서버가 여러 프론트 오리진 지원), 없으면 {@code app.oauth.base-url}로
 * {@code {baseUrl}}을 치환한 설정값을 fallback으로 쓴다.
 *
 * <p>제공자 호출이 실패하면 상태코드와 제공자가 준 에러 필드를 warn으로 남긴다. {@link BusinessException}은
 * 예외 핸들러에서 로그 없이 응답되므로, 시크릿 오류·redirect_uri 불일치 같은 설정 문제를 찾을 단서는 이 로그뿐이다.
 * 시크릿·토큰·요청 바디는 남기지 않는다.
 */
@Slf4j
@Component
public class OAuthTokenExchanger {

  private static final String BASE_URL_PLACEHOLDER = "{baseUrl}";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
  // 실패 로그에 남길 응답 필드. 토큰 교환은 OAuth 표준 error·error_description과 카카오 error_code,
  // user-info는 네이버 resultcode·message와 카카오 code·msg다. 바디를 통째로 남기지 않는 건
  // 제공자가 응답에 무엇을 더 실을지 여기서 통제할 수 없어서다.
  private static final List<String> ERROR_FIELDS =
      List.of("error", "error_code", "error_description", "resultcode", "message", "code", "msg");

  private final RestClient restClient;
  private final String baseUrl;
  private final List<String> allowedRedirectOrigins;

  public OAuthTokenExchanger(
      @Value("${app.oauth.base-url:http://localhost:8080}") String baseUrl,
      @Value("${app.oauth.allowed-redirect-origins:http://localhost:8080,http://localhost:3000}")
          List<String> allowedRedirectOrigins) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(CONNECT_TIMEOUT);
    factory.setReadTimeout(READ_TIMEOUT);
    this.restClient = RestClient.builder().requestFactory(factory).build();
    this.baseUrl = baseUrl;
    this.allowedRedirectOrigins = allowedRedirectOrigins;
  }

  /**
   * 인가코드를 토큰으로 교환한다. state가 non-blank일 때만 실어(네이버는 필수, 카카오는 여분 파라미터 무시)
   * 두 제공자를 안전하게 처리하며, 토큰 응답 Map 전체를 그대로 반환한다.
   */
  public Map<String, Object> exchangeToken(
      String tokenUri,
      String clientId,
      String clientSecret,
      String code,
      String state,
      String redirectUri) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    form.add("code", code);
    form.add("redirect_uri", redirectUri);
    // 네이버 토큰 교환은 state를 요구한다(인가 요청의 state와 일치해야 함). 카카오는 미사용이나
    // 여분 파라미터를 무시하므로, state가 있을 때만 실어 두 제공자 모두 안전하게 처리한다.
    if (state != null && !state.isBlank()) {
      form.add("state", state);
    }
    Map<String, Object> body = post(tokenUri, form);
    if (body.get("error") != null) {
      // 네이버는 토큰 교환이 실패해도(시크릿 오류·인가코드 재사용 등) HTTP 200에 error 바디를 준다.
      // access_token이 없다는 것만으로는 원인을 알 수 없어 여기서 남긴다. 응답 코드는 기존과 같은 500이다.
      log.warn("소셜 토큰 교환 실패(200 응답에 error): host={}, {}", hostOf(tokenUri), errorFieldsOf(body));
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    return body;
  }

  /**
   * 액세스 토큰으로 user-info 엔드포인트를 조회한다(Bearer GET). null·오류는 EXTERNAL_API_ERROR.
   */
  public Map<String, Object> fetchUserInfo(String userInfoUri, String accessToken) {
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
      log.warn("소셜 프로필 조회 실패: host={}, status={}, {}",
          hostOf(userInfoUri), ex.getStatusCode().value(), errorFieldsOf(ex));
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    } catch (ResourceAccessException ex) {
      // 타임아웃·연결 실패·DNS 오류
      log.warn("소셜 프로필 조회 실패(연결): host={}, cause={}", hostOf(userInfoUri), ex.getMessage());
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  /**
   * 토큰 교환에 쓸 redirect_uri를 결정한다. 프론트가 넘긴 값이 있으면 허용목록 검증 후 그대로 쓰고,
   * 없으면 설정된 {@code redirect-uri}의 {@code {baseUrl}}를 {@code app.oauth.base-url}로 치환한다.
   */
  public String resolveRedirectUri(String configuredRedirectUri, String requestedRedirectUri) {
    if (requestedRedirectUri != null && !requestedRedirectUri.isBlank()) {
      if (!isAllowedRedirectUri(requestedRedirectUri)) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
      }
      return requestedRedirectUri;
    }
    if (configuredRedirectUri == null || configuredRedirectUri.isBlank()) {
      // provider redirect-uri 설정 누락 — null을 폼에 실어 예측 불가 예외가 나기 전에 명확히 실패시킨다.
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    return configuredRedirectUri.replace(BASE_URL_PLACEHOLDER, baseUrl);
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
      // 401(카카오 KOE010 등 client_id·secret 오류)은 HttpURLConnection이 스트리밍 POST의 바디를 읽지 못해
      // "응답 바디 없음"으로 남는다. 토큰 교환의 401 자체가 자격증명 오류 신호다.
      log.warn("소셜 토큰 교환 실패: host={}, status={}, {}",
          hostOf(uri), ex.getStatusCode().value(), errorFieldsOf(ex));
      // 4xx = 유효하지 않은 인가코드 등 요청 오류, 그 외는 외부 연동 오류
      if (ex.getStatusCode().is4xxClientError()) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
      }
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    } catch (ResourceAccessException ex) {
      // 타임아웃·연결 실패·DNS 오류
      log.warn("소셜 토큰 교환 실패(연결): host={}, cause={}", hostOf(uri), ex.getMessage());
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  private static String errorFieldsOf(Map<?, ?> body) {
    String fields = ERROR_FIELDS.stream()
        .filter(body::containsKey)
        .map(key -> key + "=" + body.get(key))
        .collect(Collectors.joining(", "));
    return fields.isEmpty() ? "에러 필드 없음" : fields;
  }

  private static String errorFieldsOf(RestClientResponseException ex) {
    try {
      Map<?, ?> body = ex.getResponseBodyAs(Map.class);
      return body == null ? "응답 바디 없음" : errorFieldsOf(body);
    } catch (RestClientException | IllegalStateException parseFailure) {
      // JSON이 아닌 응답(HTML 오류 페이지 등). 원인을 남기려던 것이니 여기서 다시 실패하지 않는다.
      return "응답 바디 해석 불가";
    }
  }

  private static String hostOf(String uri) {
    try {
      String host = URI.create(uri).getHost();
      return host == null ? uri : host;
    } catch (IllegalArgumentException ex) {
      return uri;
    }
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
}
