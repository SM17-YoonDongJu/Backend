package com.soma.backend.domain.auth.service.provider.apple;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * Apple 토큰 엔드포인트용 {@code client_secret}(ES256 서명 JWT)을 생성·캐싱한다.
 *
 * <p>.p8(P-256 EC) 개인키 파싱은 첫 {@link #currentSecret()} 호출 시점에 지연 수행하므로 기동 시
 * {@code APPLE_*}가 없어도 안전하다. 생성된 secret은 만료 여유가 있으면 재사용하며 스레드 안전하다.
 * privateKey·secret은 절대 로깅하지 않는다.
 */
@Component
public class AppleClientSecretGenerator {

  private static final String EC_ALGORITHM = "EC";
  private static final String PEM_MARKER = "PRIVATE KEY";
  private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(1);

  private final AppleOAuthProperties properties;
  private volatile CachedSecret cached;

  public AppleClientSecretGenerator(AppleOAuthProperties properties) {
    this.properties = properties;
  }

  /**
   * 유효한 캐시가 있으면 재사용하고, 없으면 새로 ES256 서명한 client_secret을 반환한다.
   */
  public String currentSecret() {
    Instant now = Instant.now();
    CachedSecret current = cached;
    if (isFresh(current, now)) {
      return current.value();
    }
    synchronized (this) {
      current = cached;
      if (isFresh(current, now)) {
        return current.value();
      }
      CachedSecret regenerated = sign(now);
      cached = regenerated;
      return regenerated.value();
    }
  }

  private boolean isFresh(CachedSecret secret, Instant now) {
    return secret != null && now.isBefore(secret.expiresAt().minus(EXPIRY_MARGIN));
  }

  private CachedSecret sign(Instant now) {
    Instant expiresAt = now.plus(properties.getClientSecretTtl());
    try {
      ECPrivateKey privateKey = parsePrivateKey(properties.getPrivateKey());
      JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
          .keyID(properties.getKeyId())
          .build();
      JWTClaimsSet claims = new JWTClaimsSet.Builder()
          .issuer(properties.getTeamId())
          .issueTime(Date.from(now))
          .expirationTime(Date.from(expiresAt))
          .audience(properties.getIssuer())
          .subject(properties.getClientId())
          .build();
      SignedJWT signedJwt = new SignedJWT(header, claims);
      signedJwt.sign(new ECDSASigner(privateKey));
      return new CachedSecret(signedJwt.serialize(), expiresAt);
    } catch (JOSEException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  /**
   * .p8 개인키를 {@link ECPrivateKey}로 파싱한다. 운영 편의상 세 입력 형식을 모두 허용한다:
   * (1) 원본 PEM 텍스트, (2) {@code base64 -i AuthKey.p8}처럼 .p8 파일 전체를 base64로 인코딩한 값
   * (디코드하면 PEM 텍스트), (3) 헤더 없는 base64 본문(=DER의 base64). 어떤 형식이든 최종적으로
   * PKCS#8 DER로 정규화해 파싱한다.
   */
  private ECPrivateKey parsePrivateKey(String encodedKey) {
    try {
      if (encodedKey == null || encodedKey.isBlank()) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
      }
      String pem = encodedKey.trim();
      // 형식 (2): PEM 마커가 안 보이면 파일 전체 base64일 수 있다. 한 번 디코드해 PEM 텍스트가 나오면 그걸 쓴다.
      if (!pem.contains(PEM_MARKER)) {
        String decoded = decodeBase64ToStringOrNull(pem);
        if (decoded != null && decoded.contains(PEM_MARKER)) {
          pem = decoded;
        }
      }
      String body = pem
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replace("\\n", "")
          .replace("\\r", "")
          .replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(body);
      KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
      return (ECPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  private String decodeBase64ToStringOrNull(String value) {
    try {
      return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private record CachedSecret(String value, Instant expiresAt) {
  }
}
