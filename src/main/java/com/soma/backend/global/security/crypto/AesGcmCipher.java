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
 * <p>암호문 포맷은 {@code base64( nonce(12B) ‖ ciphertext ‖ gcmTag(16B) )}로, 값마다 새 랜덤 nonce를
 * 쓴다(같은 평문도 매번 다른 암호문). 128-bit 인증 태그로 변조를 탐지하며, 변조·형식오류·키불일치는
 * {@link ErrorCode#EXTERNAL_API_ERROR}로 던진다. 키·평문·복호문은 절대 로깅하지 않는다.
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

  private final SecretKeySpec keySpec;
  private final SecureRandom secureRandom = new SecureRandom();

  public AesGcmCipher(
      @Value("${app.crypto.apple-token-key:ZGV2LWxvY2FsLWFwcGxlLXRva2VuLWVuYy1rZXkhISE=}")
          String base64Key) {
    byte[] key = Base64.getDecoder().decode(base64Key);
    if (key.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException("app.crypto.apple-token-key는 base64 디코딩 시 32바이트(AES-256)여야 합니다");
    }
    this.keySpec = new SecretKeySpec(key, KEY_ALGORITHM);
  }

  /**
   * 평문을 암호화해 {@code base64(nonce‖ciphertext‖tag)}를 반환한다. 값마다 새 nonce를 쓴다.
   */
  public String encrypt(String plaintext) {
    try {
      byte[] nonce = new byte[NONCE_LENGTH];
      secureRandom.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, combined, 0, nonce.length);
      System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (GeneralSecurityException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }

  /**
   * {@link #encrypt(String)}가 만든 base64 문자열을 복호화한다. 변조·형식오류·키불일치는 예외.
   */
  public String decrypt(String base64) {
    try {
      byte[] combined = Base64.getDecoder().decode(base64);
      if (combined.length <= NONCE_LENGTH) {
        throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
      }
      byte[] nonce = Arrays.copyOfRange(combined, 0, NONCE_LENGTH);
      byte[] ciphertext = Arrays.copyOfRange(combined, NONCE_LENGTH, combined.length);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.EXTERNAL_API_ERROR);
    }
  }
}
