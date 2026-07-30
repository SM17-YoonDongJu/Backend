package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

@DisplayName("AesGcmCipher 단위 테스트")
class AesGcmCipherTest {

  // base64로 인코딩된 32바이트 테스트 키(AES-256). 운영 키가 아니다.
  private static final String KEY = "ZGV2LWxvY2FsLWFwcGxlLXRva2VuLWVuYy1rZXkhISE=";

  private final AesGcmCipher cipher = new AesGcmCipher(KEY);

  @Test
  @DisplayName("encrypt→decrypt 왕복은 원본 평문을 그대로 복원한다")
  void encryptThenDecrypt_roundTrips() {
    // Given
    String plaintext = "apple-refresh-token-abc.def.ghi";

    // When
    String encrypted = cipher.encrypt(plaintext);
    String decrypted = cipher.decrypt(encrypted);

    // Then
    assertThat(decrypted).isEqualTo(plaintext);
    assertThat(encrypted).isNotEqualTo(plaintext);
  }

  @Test
  @DisplayName("같은 평문도 매 호출 암호문이 달라진다(랜덤 nonce)")
  void encrypt_sameInput_producesDifferentCiphertext() {
    // Given
    String plaintext = "same-plaintext";

    // When
    String first = cipher.encrypt(plaintext);
    String second = cipher.encrypt(plaintext);

    // Then
    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
    assertThat(cipher.decrypt(second)).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("변조된 암호문은 인증 태그 검증 실패로 EXTERNAL_API_ERROR를 던진다")
  void decrypt_tampered_throws() {
    // Given: 유효한 암호문의 마지막 바이트(GCM 태그 영역)를 뒤집어 변조한다.
    byte[] raw = Base64.getDecoder().decode(cipher.encrypt("secret"));
    raw[raw.length - 1] ^= 0x01;
    String tampered = Base64.getEncoder().encodeToString(raw);

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(tampered))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("base64 형식이 아니면 EXTERNAL_API_ERROR를 던진다")
  void decrypt_notBase64_throws() {
    // When & Then
    assertThatThrownBy(() -> cipher.decrypt("!!! not base64 !!!"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
  }

  @Test
  @DisplayName("nonce 길이에 못 미치는 짧은 입력이면 EXTERNAL_API_ERROR를 던진다")
  void decrypt_tooShort_throws() {
    // Given: 5바이트(< nonce 12바이트)를 base64로 인코딩한 값.
    String tooShort = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5});

    // When & Then
    assertThatThrownBy(() -> cipher.decrypt(tooShort))
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
}
