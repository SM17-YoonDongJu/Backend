package com.soma.backend.domain.auth.service.provider.apple;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Apple(Web/Services ID) OAuth 설정. {@code app.oauth.apple} 프리픽스에 바인딩된다.
 *
 * <p>자격증명({@code teamId}·{@code keyId}·{@code clientId}·{@code privateKey})은
 * application.yml에서 빈 문자열을 기본값으로 두어 {@code APPLE_*} 환경변수가 없어도 앱이 기동된다.
 * 실제 파싱·서명은 첫 토큰 교환 시점(지연)에 이뤄진다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.oauth.apple")
public class AppleOAuthProperties {

  /**
   * Apple Developer 팀 식별자. client_secret JWT의 {@code iss} 클레임.
   */
  private String teamId;

  /**
   * .p8 개인키의 Key ID. client_secret JWT 헤더의 {@code kid}.
   */
  private String keyId;

  /**
   * Services ID(웹 클라이언트 식별자). client_secret JWT의 {@code sub}이자 토큰 교환의 client_id.
   */
  private String clientId;

  /**
   * base64로 인코딩된 .p8(PKCS#8) 개인키 본문. ES256 서명에 사용하며 절대 로깅하지 않는다.
   */
  private String privateKey;

  /**
   * Apple 인가 엔드포인트(프론트가 인가 URL 구성에 사용).
   */
  private String authorizationUri;

  /**
   * Apple 토큰 엔드포인트(인가코드 → id_token 교환).
   */
  private String tokenUri;

  /**
   * Apple 공개키(JWKS) 엔드포인트. id_token 서명 검증에 사용한다.
   */
  private String jwkSetUri;

  /**
   * id_token의 {@code iss} 기대값이자 client_secret JWT의 {@code aud}.
   */
  private String issuer;

  /**
   * 토큰 교환에 쓸 redirect_uri. {@code {baseUrl}} 치환을 지원한다.
   */
  private String redirectUri;

  /**
   * Apple 토큰 revoke 엔드포인트. 회원 탈퇴 시 저장해 둔 refresh_token을 무효화한다.
   */
  private String revokeUri;

  /**
   * client_secret JWT의 유효기간(만료 = 발급 시각 + 이 값).
   */
  private Duration clientSecretTtl;
}
