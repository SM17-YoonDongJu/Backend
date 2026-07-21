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
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.soma.backend.global.exception.BusinessException;

/**
 * {@link RestClientOAuthClient}의 토큰 교환 파라미터 검증. 로컬 스텁 서버로 토큰 요청 바디를 캡처해
 * state 포함/미포함과 redirect_uri 패스스루(허용목록 검증)를 외부 제공자 의존 없이 확인한다.
 */
class RestClientOAuthClientTest {

  private HttpServer server;
  private String baseUrl;
  private volatile String capturedTokenBody;

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/token", exchange -> {
      capturedTokenBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      respond(exchange, "{\"access_token\":\"stub-at\"}");
    });
    server.createContext("/naver-me", exchange -> respond(exchange, "{\"response\":{\"id\":\"nid-1\"}}"));
    server.createContext("/kakao-me", exchange -> respond(exchange, "{\"id\":\"kid-1\"}"));
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  @Test
  void naverTokenExchangeIncludesState() {
    RestClientOAuthClient client = clientFor("naver", "/naver-me", "response");

    OAuthProfile profile = client.fetchProfile("naver", "code123", "state456", null);

    Assertions.assertThat(profile.provider()).isEqualTo("naver");
    Assertions.assertThat(profile.providerUserId()).isEqualTo("nid-1");
    Assertions.assertThat(capturedTokenBody).contains("grant_type=authorization_code");
    Assertions.assertThat(capturedTokenBody).contains("code=code123");
    Assertions.assertThat(capturedTokenBody).contains("state=state456");
  }

  @Test
  void kakaoTokenExchangeIncludesState() {
    RestClientOAuthClient client = clientFor("kakao", "/kakao-me", "id");

    OAuthProfile profile = client.fetchProfile("kakao", "code123", "state789", null);

    Assertions.assertThat(profile.providerUserId()).isEqualTo("kid-1");
    Assertions.assertThat(capturedTokenBody).contains("state=state789");
  }

  @Test
  void tokenExchangeOmitsStateWhenAbsent() {
    RestClientOAuthClient client = clientFor("kakao", "/kakao-me", "id");

    client.fetchProfile("kakao", "code123", null, null);

    Assertions.assertThat(capturedTokenBody).doesNotContain("state=");
  }

  @Test
  void usesProvidedRedirectUriWhenAllowed() {
    RestClientOAuthClient client = clientFor("naver", "/naver-me", "response");

    client.fetchProfile("naver", "code123", "state456", "http://localhost:8080/login/oauth2/code/naver");

    Assertions.assertThat(capturedTokenBody).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080");
  }

  @Test
  void rejectsDisallowedRedirectUri() {
    RestClientOAuthClient client = clientFor("naver", "/naver-me", "response");

    Assertions.assertThatThrownBy(() -> client.fetchProfile(
            "naver", "code123", "state456", "http://evil.com/login/oauth2/code/naver"))
        .isInstanceOf(BusinessException.class);
  }

  private RestClientOAuthClient clientFor(String provider, String userInfoPath, String userNameAttr) {
    ClientRegistration registration = ClientRegistration.withRegistrationId(provider)
        .clientId(provider + "-id")
        .clientSecret(provider + "-secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .authorizationUri(baseUrl + "/authorize")
        .redirectUri("http://localhost:3000/login/oauth2/code/" + provider)
        .tokenUri(baseUrl + "/token")
        .userInfoUri(baseUrl + userInfoPath)
        .userNameAttributeName(userNameAttr)
        .build();
    ClientRegistrationRepository repository =
        registrationId -> provider.equals(registrationId) ? registration : null;
    return new RestClientOAuthClient(
        repository, "http://localhost:3000", List.of("http://localhost:3000", "http://localhost:8080"));
  }

  private void respond(HttpExchange exchange, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
