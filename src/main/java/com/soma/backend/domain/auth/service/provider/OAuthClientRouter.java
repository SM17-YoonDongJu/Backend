package com.soma.backend.domain.auth.service.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * {@link OAuthClient} 파사드. 주입받은 {@link OAuthProviderStrategy} 목록을 provider 키로 색인해
 * provider별 전략에 위임한다. 미지원 provider는 400 {@code UNSUPPORTED_PROVIDER}.
 */
@Component
public class OAuthClientRouter implements OAuthClient {

  private final Map<String, OAuthProviderStrategy> strategies;

  public OAuthClientRouter(List<OAuthProviderStrategy> strategies) {
    this.strategies = strategies.stream()
        .collect(Collectors.toMap(OAuthProviderStrategy::provider, Function.identity()));
  }

  @Override
  public OAuthProfile fetchProfile(String provider, String code, String state, String redirectUri) {
    OAuthProviderStrategy strategy = strategies.get(provider);
    if (strategy == null) {
      throw new BusinessException(ErrorCode.UNSUPPORTED_PROVIDER);
    }
    return strategy.fetchProfile(code, state, redirectUri);
  }
}
