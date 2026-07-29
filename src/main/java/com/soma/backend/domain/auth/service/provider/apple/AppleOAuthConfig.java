package com.soma.backend.domain.auth.service.provider.apple;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Apple 전용 OAuth 빈 구성. {@link AppleOAuthProperties}를 등록하고 Apple id_token 검증용
 * {@link JwtDecoder}(Apple JWKS 서명검증 + iss·exp·aud 검증)를 제공한다.
 *
 * <p>{@code NimbusJwtDecoder.withJwkSetUri(...)}는 JWKS를 첫 검증 시점에 지연 조회하므로 이 빈 생성이
 * 기동 시 Apple로 네트워크 호출을 하지 않는다({@code APPLE_*} 미설정 로컬에서도 안전).
 */
@Configuration
@EnableConfigurationProperties(AppleOAuthProperties.class)
public class AppleOAuthConfig {

  @Bean
  public JwtDecoder appleJwtDecoder(AppleOAuthProperties properties) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
    OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
        new JwtIssuerValidator(properties.getIssuer()),
        new JwtTimestampValidator(),
        audienceValidator(properties.getClientId()));
    decoder.setJwtValidator(validator);
    return decoder;
  }

  private OAuth2TokenValidator<Jwt> audienceValidator(String clientId) {
    OAuth2Error error = new OAuth2Error("invalid_token", "The required audience is missing", null);
    return jwt -> {
      List<String> audiences = jwt.getAudience();
      if (audiences != null && audiences.contains(clientId)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(error);
    };
  }
}
