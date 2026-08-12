package com.soma.backend.global.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * AES-256-GCM 대칭 암복호화기. 저장 전 민감값(Apple refresh_token 등)을 암호화하고, 사용 직전에만
 * 복호화한다(평문 at-rest 0).
 *
 * <p>암호문 포맷은 {@code "v2:" + base64( nonce(12B) ‖ ciphertext ‖ gcmTag(16B) )}로, 값마다 새 랜덤
 * nonce를 쓴다(같은 평문도 매번 다른 암호문). AAD로 행 식별자를 묶어 다른 행에 잘못 들어간 암호문이
 * 복호화되는 것을 차단한다(base64 알파벳에 {@code :}가 없어 prefix 판별은 모호하지 않다).
 *
 * <p>{@code v2:} prefix가 없는 값은 AAD 도입 전 구버전 암호문으로 간주해 AAD 없이 복호화한다(legacy
 * 폴백). v2 암호문의 prefix를 떼어 legacy 경로로 넣는 다운그레이드는 암호화 때 AAD가 GCM 태그에
 * 반영돼 있어 태그 검증이 실패한다.
 *
 * <p>128-bit 인증 태그로 변조를 탐지하며, 변조·AAD 불일치·형식오류·키불일치는
 * {@link ErrorCode#EXTERNAL_API_ERROR}로 던진다. 키·평문·복호문·AAD 원문은 절대 로깅하지 않는다.
 *
 * <p>키는 base64로 인코딩된 32바이트(AES-256)를 {@code app.crypto.apple-token-key}에서 주입받는다.
 * 로컬/테스트 기동용 dev 기본값이 있으나, 운영은 반드시 {@code APPLE_TOKEN_ENC_KEY} env로 교체한다.
 */
@Component
public class AesGcmCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int NONCE_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;
  private static final int KEY_LENGTH_BYTES = 32;
  private static final String AAD_BOUND_PREFIX = "v2:";
  private static final String REFRESH_TOKEN_AAD_PREFIX = "social_accounts:refresh_token:";

  private final SecretKeySpec keySpec;
  private final SecureRandom secureRandom = new SecureRandom();

  public AesGcmCipher(@Value("${app.crypto.apple-token-key:}") String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalStateException(
          "app.crypto.apple-token-key(APPLE_TOKEN_ENC_KEY)가 설정되지 않았습니다 — 운영은 필수");
    }
    byte[] key = Base64.getDecoder().decode(base64Key);
    if (key.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException("app.crypto.apple-token-key는 base64 디코딩 시 32바이트(AES-256)여야 합니다");
    }
    this.keySpec = new SecretKeySpec(key, KEY_ALGORITHM);
  }

  /**
   * refresh_token 행 바인딩 AAD를 만든다. SocialAccount.id는 암호화 시점(OAuth 콜백, 가입 전 스테이징)엔
   * 존재하지 않으므로, 스테이징→가입→탈퇴 revoke 전 구간에서 불변인 자연키 (provider, providerUserId)로
   * 행을 식별한다. 포맷 정의는 이 한 곳뿐이다 — 호출부는 반드시 이 메서드로 AAD를 만든다.
   */
  public static String appleRefreshTokenAad(String provider, String providerUserId) {
    return REFRESH_TOKEN_AAD_PREFIX + provider + ":" + providerUserId;
  }

  /**
   * 평문을 AAD와 묶어 암호화해 {@code "v2:" + base64(nonce‖ciphertext‖tag)}를 반환한다. 값마다 새 nonce를
   * 쓴다. AAD는 {@link #appleRefreshTokenAad(String, String)}로 만든 행 식별자여야 하며, 복호화 시 같은
   * 값을 제시해야만 성공한다.
   */
  public String encrypt(String plaintext, String aad) {
    if (aad == null || aad.isBlank()) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
    try {
      byte[] nonce = new byte[NONCE_LENGTH];
      secureRandom.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, combined, 0, nonce.length);
      System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
      return AAD_BOUND_PREFIX + Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  /**
   * {@link #encrypt(String, String)}가 만든 값을 복호화한다. {@code v2:} prefix가 있으면 제시된 AAD와
   * 묶어 검증하고(불일치 시 실패), 없으면 AAD 도입 전 구버전 암호문으로 간주해 AAD 없이 복호화한다.
   * 변조·AAD 불일치·형식오류·키불일치는 예외.
   */
  public String decrypt(String value, String aad) {
    if (value != null && value.startsWith(AAD_BOUND_PREFIX)) {
      if (aad == null || aad.isBlank()) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
      }
      return doDecrypt(value.substring(AAD_BOUND_PREFIX.length()), aad);
    }
    return doDecrypt(value, null);
  }

  private String doDecrypt(String base64, String aad) {
    try {
      byte[] combined = Base64.getDecoder().decode(base64);
      if (combined.length <= NONCE_LENGTH) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
      }
      byte[] nonce = Arrays.copyOfRange(combined, 0, NONCE_LENGTH);
      byte[] ciphertext = Arrays.copyOfRange(combined, NONCE_LENGTH, combined.length);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      if (aad != null) {
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      }
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }
}
