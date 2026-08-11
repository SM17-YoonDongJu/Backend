package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@DisplayName("AesGcmCipher 단위 테스트")
class AesGcmCipherTest {

  // base64로 인코딩된 32바이트 테스트 키(AES-256). 운영 키가 아니다.
  private static final String KEY = "ZGV2LWxvY2FsLWFwcGxlLXRva2VuLWVuYy1rZXkhISE=";
  private static final String AAD = AesGcmCipher.appleRefreshTokenAad("apple", "apple-1");

  private final AesGcmCipher cipher = new AesGcmCipher(KEY);

  @Test
  @DisplayName("encrypt→같은 AAD decrypt 왕복은 원본 평문을 그대로 복원한다")
  void encryptThenDecrypt_sameAad_roundTrips() {
    // Given
    String plaintext = "apple-refresh-token-abc.def.ghi";

    // When
    String encrypted = cipher.encrypt(plaintext, AAD);
    String decrypted = cipher.decrypt(encrypted, AAD);

    // Then
    assertThat(decrypted).isEqualTo(plaintext);
    assertThat(encrypted).isNotEqualTo(plaintext);
    assertThat(encrypted).startsWith("v2:");
  }

  @Test
  @DisplayName("다른 행의 AAD로 복호화하면 태그 검증 실패로 EXTERNAL_API_ERROR를 던진다")
  void decrypt_differentRowAad_throws() {
    // Given: apple-1 행에 바인딩된 암호문을 apple-2 행의 AAD로 복호화 시도(행 치환 시나리오).
    String encrypted = cipher.encrypt("secret", AAD);
    String otherRowAad = AesGcmCipher.appleRefreshTokenAad("apple", "apple-2");

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(encrypted, otherRowAad))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("AAD 도입 전 구버전 암호문(v2 prefix 없음)은 AAD 없이 복호화된다(legacy 폴백)")
  void decrypt_legacyCiphertext_fallsBackWithoutAad() throws Exception {
    // Given: 구버전 포맷 base64(nonce‖ct‖tag) — AAD 없이 직접 암호화해 재현한다.
    String legacy = encryptLegacyWithoutAad("legacy-refresh-token");

    // When: 현재 행의 AAD를 제시해도(legacy 경로에선 무시) 정상 복호화된다.
    String decrypted = cipher.decrypt(legacy, AAD);

    // Then
    assertThat(decrypted).isEqualTo("legacy-refresh-token");
  }

  @Test
  @DisplayName("v2 암호문의 prefix를 떼어 legacy 경로로 강제해도 태그 검증 실패로 던진다(다운그레이드 차단)")
  void decrypt_strippedV2Prefix_throws() {
    // Given: AAD가 GCM 태그에 반영돼 있어 prefix만 떼어내도 AAD 없는 복호화는 실패해야 한다.
    String encrypted = cipher.encrypt("secret", AAD);
    String stripped = encrypted.substring("v2:".length());

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(stripped, AAD))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("v2 암호문을 AAD 없이 복호화하려 하면 EXTERNAL_API_ERROR를 던진다")
  void decrypt_v2WithoutAad_throws() {
    // Given
    String encrypted = cipher.encrypt("secret", AAD);

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(encrypted, null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("AAD 없이 암호화하려 하면 EXTERNAL_API_ERROR를 던진다")
  void encrypt_withoutAad_throws() {
    // When & Then
    assertThatThrownBy(() -> cipher.encrypt("secret", null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("같은 평문·AAD도 매 호출 암호문이 달라진다(랜덤 nonce)")
  void encrypt_sameInput_producesDifferentCiphertext() {
    // Given
    String plaintext = "same-plaintext";

    // When
    String first = cipher.encrypt(plaintext, AAD);
    String second = cipher.encrypt(plaintext, AAD);

    // Then
    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(first, AAD)).isEqualTo(plaintext);
    assertThat(cipher.decrypt(second, AAD)).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("변조된 암호문은 인증 태그 검증 실패로 EXTERNAL_API_ERROR를 던진다")
  void decrypt_tampered_throws() {
    // Given: 유효한 암호문의 마지막 바이트(GCM 태그 영역)를 뒤집어 변조한다.
    String encrypted = cipher.encrypt("secret", AAD);
    byte[] raw = Base64.getDecoder().decode(encrypted.substring("v2:".length()));
    raw[raw.length - 1] ^= 0x01;
    String tampered = "v2:" + Base64.getEncoder().encodeToString(raw);

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(tampered, AAD))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("base64 형식이 아니면 EXTERNAL_API_ERROR를 던진다")
  void decrypt_notBase64_throws() {
    // When & Then
    assertThatThrownBy(() -> cipher.decrypt("!!! not base64 !!!", AAD))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("nonce 길이에 못 미치는 짧은 입력이면 EXTERNAL_API_ERROR를 던진다")
  void decrypt_tooShort_throws() {
    // Given: 5바이트(< nonce 12바이트)를 base64로 인코딩한 값.
    String tooShort = "v2:" + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5});

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(tooShort, AAD))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("키가 32바이트가 아니면 생성 시 IllegalStateException을 던진다")
  void construct_wrongKeyLength_throws() {
    // Given: 16바이트 키(base64).
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    // When & Then
    assertThatThrownBy(() -> new AesGcmCipher(shortKey))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * AAD 도입 전 {@code encrypt(String)}이 만들던 구버전 포맷 base64(nonce‖ct‖tag)를 재현한다.
   */
  private String encryptLegacyWithoutAad(String plaintext) throws Exception {
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher legacyCipher = Cipher.getInstance("AES/GCM/NoPadding");
    SecretKeySpec keySpec = new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES");
    legacyCipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, nonce));
    byte[] ciphertext = legacyCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] combined = new byte[nonce.length + ciphertext.length];
    System.arraycopy(nonce, 0, combined, 0, nonce.length);
    System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
    return Base64.getEncoder().encodeToString(combined);
  }
}
