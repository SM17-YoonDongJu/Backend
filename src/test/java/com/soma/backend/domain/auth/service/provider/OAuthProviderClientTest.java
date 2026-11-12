package com.soma.backend.domain.auth.service.provider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 카카오/네이버 전략을 실제 {@link OAuthTokenExchanger}로 조립해 토큰 교환 파라미터를 검증한다.
 * 로컬 스텁 서버로 토큰 요청 바디를 캡처해 state 포함/미포함, redirect_uri 패스스루(허용목록 검증),
 * kakao {@code id}/naver {@code response.id} 파싱을 외부 제공자 의존 없이 확인한다.
 *
 * <p>제공자가 실패를 돌려줄 때는 기존 응답 코드를 유지하면서, 원인을 찾을 수 있게 상태코드·에러 필드를
 * warn으로 남기는지(시크릿·토큰은 빼고) 확인한다.
 */
class OAuthProviderClientTest {

  private static final String JSON = "application/json";

  private HttpServer server;
  private String baseUrl;
  private volatile String capturedTokenBody;
  private Logger exchangerLogger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/token", exchange -> {
      capturedTokenBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      respond(exchange, 200, JSON, "{\"access_token\":\"stub-at\"}");
    });
    // 네이버는 토큰 교환이 실패해도 HTTP 200에 error 바디를 준다(실측 응답 그대로).
    server.createContext("/token-naver-error", exchange -> respond(exchange, 200, JSON,
        "{\"error\":\"invalid_request\",\"error_description\":\"no valid data in session\"}"));
    // 카카오는 인가코드가 잘못되면 400(KOE320), client_id·secret이 틀리면 401(KOE010)을 준다.
    server.createContext("/token-400", exchange -> respond(exchange, 400, JSON,
        "{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found\","
            + "\"error_code\":\"KOE320\"}"));
    server.createContext("/token-401", exchange -> respond(exchange, 401, JSON,
        "{\"error\":\"invalid_client\",\"error_description\":\"Bad client credentials\",\"error_code\":\"KOE010\"}"));
    server.createContext("/token-html-502", exchange ->
        respond(exchange, 502, "text/html", "<html>Bad Gateway</html>"));
    server.createContext("/naver-me", exchange -> respond(exchange, 200, JSON, "{\"response\":{\"id\":\"nid-1\"}}"));
    server.createContext("/naver-me-401", exchange -> respond(exchange, 401, JSON,
        "{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}"));
    server.createContext("/kakao-me", exchange -> respond(exchange, 200, JSON, "{\"id\":\"kid-1\"}"));
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();

    exchangerLogger = (Logger) LoggerFactory.getLogger(OAuthTokenExchanger.class);
    appender = new ListAppender<>();
    appender.start();
    exchangerLogger.addAppender(appender);
  }

  @AfterEach
  void stopStub() {
    exchangerLogger.detachAppender(appender);
    server.stop(0);
  }

  @Test
  void naverTokenExchangeIncludesState() {
    OAuthProviderStrategy client = clientFor("naver", "/naver-me", "response");

    OAuthProfile profile = client.fetchProfile("code123", "state456", null);

    Assertions.assertThat(profile.provider()).isEqualTo("naver");
    Assertions.assertThat(profile.providerUserId()).isEqualTo("nid-1");
    Assertions.assertThat(capturedTokenBody).contains("grant_type=authorization_code");
    Assertions.assertThat(capturedTokenBody).contains("code=code123");
    Assertions.assertThat(capturedTokenBody).contains("state=state456");
    Assertions.assertThat(warnMessages()).isEmpty();
  }

  @Test
  void kakaoTokenExchangeIncludesState() {
    OAuthProviderStrategy client = clientFor("kakao", "/kakao-me", "id");

    OAuthProfile profile = client.fetchProfile("code123", "state789", null);

    Assertions.assertThat(profile.provider()).isEqualTo("kakao");
    Assertions.assertThat(profile.providerUserId()).isEqualTo("kid-1");
    Assertions.assertThat(capturedTokenBody).contains("state=state789");
  }

  @Test
  void tokenExchangeOmitsStateWhenAbsent() {
    OAuthProviderStrategy client = clientFor("kakao", "/kakao-me", "id");

    client.fetchProfile("code123", null, null);

    Assertions.assertThat(capturedTokenBody).doesNotContain("state=");
  }

  @Test
  void usesProvidedRedirectUriWhenAllowed() {
    OAuthProviderStrategy client = clientFor("naver", "/naver-me", "response");

    client.fetchProfile("code123", "state456", "http://localhost:8080/login/oauth2/code/naver");

    Assertions.assertThat(capturedTokenBody).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080");
  }

  @Test
  void rejectsDisallowedRedirectUri() {
    OAuthProviderStrategy client = clientFor("naver", "/naver-me", "response");

    Assertions.assertThatThrownBy(() -> client.fetchProfile(
            "code123", "state456", "http://evil.com/login/oauth2/code/naver"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void naverErrorBodyOn200IsLoggedAndStillFailsAsExternalApiError() {
    OAuthProviderStrategy client = clientFor("naver", "/token-naver-error", "/naver-me", "response");

    Assertions.assertThatThrownBy(() -> client.fetchProfile("code123", "state456", null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_ERROR));

    Assertions.assertThat(warnMessages()).singleElement().asString()
        .contains("host=localhost")
        .contains("error=invalid_request")
        .contains("error_description=no valid data in session")
        .doesNotContain("naver-secret");
  }

  @Test
  void tokenErrorStatusIsLoggedAndStillFailsAsInvalidRequest() {
    OAuthProviderStrategy client = clientFor("kakao", "/token-400", "/kakao-me", "id");

    Assertions.assertThatThrownBy(() -> client.fetchProfile("code123", null, null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

    Assertions.assertThat(warnMessages()).singleElement().asString()
        .contains("status=400")
        .contains("error=invalid_grant")
        .contains("error_code=KOE320")
        .doesNotContain("kakao-secret");
  }

  @Test
  void tokenUnauthorizedIsLoggedWithStatus() {
    // HttpURLConnection은 스트리밍 POST가 401을 받으면 바디를 읽지 못한다. 바디 필드는 기대하지 않고,
    // client_id·secret 오류의 신호인 상태코드가 남는지만 본다.
    OAuthProviderStrategy client = clientFor("kakao", "/token-401", "/kakao-me", "id");

    Assertions.assertThatThrownBy(() -> client.fetchProfile("code123", null, null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

    Assertions.assertThat(warnMessages()).singleElement().asString()
        .contains("소셜 토큰 교환 실패")
        .contains("status=401")
        .doesNotContain("kakao-secret");
  }

  @Test
  void userInfoErrorStatusIsLoggedWithoutAccessToken() {
    OAuthProviderStrategy client = clientFor("naver", "/token", "/naver-me-401", "response");

    Assertions.assertThatThrownBy(() -> client.fetchProfile("code123", "state456", null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_ERROR));

    Assertions.assertThat(warnMessages()).singleElement().asString()
        .contains("소셜 프로필 조회 실패")
        .contains("status=401")
        .contains("resultcode=024")
        .doesNotContain("stub-at");
  }

  @Test
  void nonJsonErrorBodyStillLogsStatus() {
    OAuthProviderStrategy client = clientFor("kakao", "/token-html-502", "/kakao-me", "id");

    Assertions.assertThatThrownBy(() -> client.fetchProfile("code123", null, null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> Assertions.assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_ERROR));

    Assertions.assertThat(warnMessages()).singleElement().asString()
        .contains("status=502")
        .contains("응답 바디 해석 불가");
  }

  private OAuthProviderStrategy clientFor(String provider, String userInfoPath, String userNameAttr) {
    return clientFor(provider, "/token", userInfoPath, userNameAttr);
  }

  private OAuthProviderStrategy clientFor(
      String provider, String tokenPath, String userInfoPath, String userNameAttr) {
    ClientRegistration registration = ClientRegistration.withRegistrationId(provider)
        .clientId(provider + "-id")
        .clientSecret(provider + "-secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationUri(baseUrl + "/authorize")
        .redirectUri("http://localhost:3000/login/oauth2/code/" + provider)
        .tokenUri(baseUrl + tokenPath)
        .userInfoUri(baseUrl + userInfoPath)
        .userNameAttributeName(userNameAttr)
        .build();
    ClientRegistrationRepository repository =
        registrationId -> provider.equals(registrationId) ? registration : null;
    OAuthTokenExchanger exchanger = new OAuthTokenExchanger(
        "http://localhost:3000", List.of("http://localhost:3000", "http://localhost:8080"));
    if ("kakao".equals(provider)) {
      return new KakaoOAuthClient(repository, exchanger);
    }
    return new NaverOAuthClient(repository, exchanger);
  }

  private List<String> warnMessages() {
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private void respond(HttpExchange exchange, int status, String contentType, String payload) throws IOException {
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
