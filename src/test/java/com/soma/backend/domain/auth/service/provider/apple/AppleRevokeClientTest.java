package com.soma.backend.domain.auth.service.provider.apple;

import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sun.net.httpserver.HttpServer;

import com.soma.backend.global.exception.BusinessException;

/**
 * {@link AppleRevokeClient}의 revoke 요청 검증. 로컬 스텁 서버로 form 바디를 캡처해 token·client_id·
 * token_type_hint 전송과 비2xx 시 예외 변환을 외부 Apple 의존 없이 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class AppleRevokeClientTest {

  @Mock
  private AppleClientSecretGenerator clientSecretGenerator;

  private HttpServer server;
  private final AtomicReference<String> capturedBody = new AtomicReference<>();
  private volatile int responseStatus;

  @BeforeEach
  void startStub() throws IOException {
    responseStatus = 200;
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/revoke", exchange -> {
      capturedBody.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(responseStatus, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private AppleRevokeClient clientFor() {
    AppleOAuthProperties properties = new AppleOAuthProperties();
    properties.setClientId("com.soma.web");
    properties.setRevokeUri("http://localhost:" + server.getAddress().getPort() + "/revoke");
    return new AppleRevokeClient(properties, clientSecretGenerator);
  }

  @Test
  void revokeSendsTokenAndClientCredentials() {
    given(clientSecretGenerator.currentSecret()).willReturn("stub-secret");
    AppleRevokeClient client = clientFor();

    client.revoke("apple-refresh-1");

    Assertions.assertThat(capturedBody.get()).contains("token=apple-refresh-1");
    Assertions.assertThat(capturedBody.get()).contains("client_id=com.soma.web");
    Assertions.assertThat(capturedBody.get()).contains("client_secret=stub-secret");
    Assertions.assertThat(capturedBody.get()).contains("token_type_hint=refresh_token");
  }

  @Test
  void revokeThrowsBusinessExceptionOnNon2xx() {
    given(clientSecretGenerator.currentSecret()).willReturn("stub-secret");
    responseStatus = 400;
    AppleRevokeClient client = clientFor();

    Assertions.assertThatThrownBy(() -> client.revoke("apple-refresh-1"))
        .isInstanceOf(BusinessException.class);
  }
}
