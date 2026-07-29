package com.soma.backend.domain.auth.service.provider.apple;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.soma.backend.domain.auth.service.provider.OAuthProfile;
import com.soma.backend.domain.auth.service.provider.OAuthTokenExchanger;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * generator·verifier를 mock으로 두고 실제 {@link OAuthTokenExchanger}를 로컬 스텁 토큰 엔드포인트에
 * 조립해 {@link AppleOAuthClient}를 검증한다. 교환 바디(code·client_secret) 캡처와 id_token 누락 처리를 확인한다.
 */
class AppleOAuthClientTest {

  private HttpServer server;
  private String baseUrl;
  private volatile String tokenResponse;
  private volatile String capturedTokenBody;

  @BeforeEach
  void startStub() throws IOException {
    tokenResponse = "{\"id_token\":\"stub-id-token\"}";
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/token", exchange -> {
      capturedTokenBody =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      respond(exchange, tokenResponse);
    });
    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  @Test
  void fetchProfileExchangesCodeAndReturnsAppleSubject() {
    AppleOAuthClient client = client();

    OAuthProfile profile = client.fetchProfile("code123", "state1", null);

    Assertions.assertThat(profile.provider()).isEqualTo("apple");
    Assertions.assertThat(profile.providerUserId()).isEqualTo("apple-sub-1");
    Assertions.assertThat(capturedTokenBody).contains("code=code123");
    Assertions.assertThat(capturedTokenBody).contains("client_secret=stub-secret");
  }

  @Test
  void fetchProfileFailsWhenIdTokenMissing() {
    tokenResponse = "{\"access_token\":\"at\"}";
    AppleOAuthClient client = client();

    Assertions.assertThatThrownBy(() -> client.fetchProfile("code123", "state1", null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  private AppleOAuthClient client() {
    AppleOAuthProperties properties = new AppleOAuthProperties();
    properties.setClientId("apple-client-id");
    properties.setTokenUri(baseUrl + "/token");
    properties.setRedirectUri("http://localhost:3000/login/oauth2/code/apple");

    AppleClientSecretGenerator secretGenerator = Mockito.mock(AppleClientSecretGenerator.class);
    Mockito.when(secretGenerator.currentSecret()).thenReturn("stub-secret");
    AppleIdTokenVerifier verifier = Mockito.mock(AppleIdTokenVerifier.class);
    Mockito.when(verifier.verifyAndGetSubject(Mockito.anyString())).thenReturn("apple-sub-1");

    OAuthTokenExchanger exchanger = new OAuthTokenExchanger(
        "http://localhost:3000", List.of("http://localhost:3000", "http://localhost:8080"));
    return new AppleOAuthClient(properties, secretGenerator, verifier, exchanger);
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
