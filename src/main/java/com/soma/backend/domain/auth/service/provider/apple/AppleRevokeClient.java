package com.soma.backend.domain.auth.service.provider.apple;

import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.outbox.AppleTokenRevoker;

/**
 * Apple 토큰 revoke 클라이언트. 탈퇴 시 아웃박스 소비자가 복호화한 refresh_token으로 Apple revoke
 * 엔드포인트에 form-POST 한다. Apple은 성공 시 빈 200을 준다.
 *
 * <p>비2xx·타임아웃·연결오류는 {@link ErrorCode#EXTERNAL_API_ERROR}로 던져 아웃박스가 재시도하게 한다.
 * refresh_token·client_secret은 절대 로깅하지 않는다.
 */
@Component
public class AppleRevokeClient implements AppleTokenRevoker {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
  private static final String REFRESH_TOKEN_HINT = "refresh_token";

  private final AppleOAuthProperties properties;
  private final AppleClientSecretGenerator clientSecretGenerator;
  private final RestClient restClient;

  public AppleRevokeClient(
      AppleOAuthProperties properties, AppleClientSecretGenerator clientSecretGenerator) {
    this.properties = properties;
    this.clientSecretGenerator = clientSecretGenerator;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(CONNECT_TIMEOUT);
    factory.setReadTimeout(READ_TIMEOUT);
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public void revoke(String refreshToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", properties.getClientId());
    form.add("client_secret", clientSecretGenerator.currentSecret());
    form.add("token", refreshToken);
    form.add("token_type_hint", REFRESH_TOKEN_HINT);
    try {
      restClient.post()
          .uri(properties.getRevokeUri())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException | ResourceAccessException ex) {
      // RestClientResponseException = 비2xx 응답, ResourceAccessException = 타임아웃·연결 실패·DNS 오류
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }
}
