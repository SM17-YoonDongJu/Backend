package com.soma.backend.domain.auth.service.provider.apple;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 로컬 P-256 EC 키페어로 {@link AppleClientSecretGenerator}의 client_secret 생성을 검증한다.
 * 헤더(alg ES256·kid)·클레임(iss·sub·aud·exp)·서명·캐시 재사용을 실제 Apple 접속 없이 확인한다.
 */
class AppleClientSecretGeneratorTest {

  private static final String TEAM_ID = "TEAM123456";
  private static final String KEY_ID = "KEY7890AB";
  private static final String CLIENT_ID = "com.example.web";
  private static final String ISSUER = "https://appleid.apple.com";

  private KeyPair keyPair;
  private AppleClientSecretGenerator generator;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
    keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    keyPair = keyPairGenerator.generateKeyPair();
    String encodedPrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

    AppleOAuthProperties properties = new AppleOAuthProperties();
    properties.setTeamId(TEAM_ID);
    properties.setKeyId(KEY_ID);
    properties.setClientId(CLIENT_ID);
    properties.setIssuer(ISSUER);
    properties.setPrivateKey(encodedPrivateKey);
    properties.setClientSecretTtl(Duration.ofMinutes(30));

    generator = new AppleClientSecretGenerator(properties);
  }

  @Test
  void currentSecretSignsEs256ClientSecretJwt() throws Exception {
    String secret = generator.currentSecret();

    SignedJWT signedJwt = SignedJWT.parse(secret);
    Assertions.assertThat(signedJwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    Assertions.assertThat(signedJwt.getHeader().getKeyID()).isEqualTo(KEY_ID);
    Assertions.assertThat(signedJwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic())))
        .isTrue();

    JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
    Assertions.assertThat(claims.getIssuer()).isEqualTo(TEAM_ID);
    Assertions.assertThat(claims.getSubject()).isEqualTo(CLIENT_ID);
    Assertions.assertThat(claims.getAudience()).contains(ISSUER);

    Date issuedAt = claims.getIssueTime();
    Date expiresAt = claims.getExpirationTime();
    Assertions.assertThat(issuedAt).isNotNull();
    Assertions.assertThat(expiresAt).isAfter(issuedAt);
  }

  @Test
  void currentSecretCachesGeneratedToken() {
    String first = generator.currentSecret();
    String second = generator.currentSecret();

    Assertions.assertThat(second).isEqualTo(first);
  }

  @Test
  void currentSecretAcceptsBase64EncodedP8File() throws Exception {
    // `base64 -i AuthKey.p8` 형식(.p8 PEM 파일 전체를 base64로 인코딩한 값)도 정상 서명돼야 한다.
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
    String base64OfP8File = Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));

    AppleOAuthProperties properties = new AppleOAuthProperties();
    properties.setTeamId(TEAM_ID);
    properties.setKeyId(KEY_ID);
    properties.setClientId(CLIENT_ID);
    properties.setIssuer(ISSUER);
    properties.setPrivateKey(base64OfP8File);
    properties.setClientSecretTtl(Duration.ofMinutes(30));
    AppleClientSecretGenerator fileKeyGenerator = new AppleClientSecretGenerator(properties);

    SignedJWT signedJwt = SignedJWT.parse(fileKeyGenerator.currentSecret());
    Assertions.assertThat(signedJwt.verify(new ECDSAVerifier((ECPublicKey) keyPair.getPublic())))
        .isTrue();
  }
}
