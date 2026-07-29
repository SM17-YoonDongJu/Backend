package com.soma.backend.domain.auth.service.provider.apple;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 로컬 RSA 키로 서명한 테스트 id_token과 동일 validator 조합의 {@link NimbusJwtDecoder}를 주입해
 * {@link AppleIdTokenVerifier}를 검증한다. 유효 토큰→sub, aud 불일치·만료→EXTERNAL_API_ERROR.
 */
class AppleIdTokenVerifierTest {

  private static final String ISSUER = "https://appleid.apple.com";
  private static final String CLIENT_ID = "com.example.web";
  private static final String SUBJECT = "apple-sub-1";

  private KeyPair keyPair;
  private AppleIdTokenVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(2048);
    keyPair = keyPairGenerator.generateKeyPair();
    verifier = new AppleIdTokenVerifier(decoder());
  }

  @Test
  void verifyAndGetSubjectReturnsSubjectForValidToken() throws Exception {
    Instant now = Instant.now();
    String idToken = signToken(ISSUER, CLIENT_ID, now, now.plus(5, ChronoUnit.MINUTES));

    Assertions.assertThat(verifier.verifyAndGetSubject(idToken)).isEqualTo(SUBJECT);
  }

  @Test
  void verifyAndGetSubjectRejectsAudienceMismatch() throws Exception {
    Instant now = Instant.now();
    String idToken = signToken(ISSUER, "com.other.app", now, now.plus(5, ChronoUnit.MINUTES));

    Assertions.assertThatThrownBy(() -> verifier.verifyAndGetSubject(idToken))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  void verifyAndGetSubjectRejectsExpiredToken() throws Exception {
    Instant now = Instant.now();
    String idToken = signToken(
        ISSUER, CLIENT_ID, now.minus(10, ChronoUnit.MINUTES), now.minus(5, ChronoUnit.MINUTES));

    Assertions.assertThatThrownBy(() -> verifier.verifyAndGetSubject(idToken))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  private NimbusJwtDecoder decoder() {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    OAuth2Error error = new OAuth2Error("invalid_token", "The required audience is missing", null);
    OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
      List<String> audiences = jwt.getAudience();
      if (audiences != null && audiences.contains(CLIENT_ID)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(error);
    };
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        new JwtIssuerValidator(ISSUER), new JwtTimestampValidator(), audienceValidator));
    return decoder;
  }

  private String signToken(String issuer, String audience, Instant issuedAt, Instant expiresAt)
      throws Exception {
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).build();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .subject(SUBJECT)
        .issueTime(Date.from(issuedAt))
        .expirationTime(Date.from(expiresAt))
        .build();
    SignedJWT signedJwt = new SignedJWT(header, claims);
    signedJwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
    return signedJwt.serialize();
  }
}
