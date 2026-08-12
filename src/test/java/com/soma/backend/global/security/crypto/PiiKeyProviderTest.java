package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DEK 공급자·운영 가드 fail-fast 테스트(design.md §12.3 C19~C23).
 *
 * <p>운영에서 raw 키 경로로 조용히 떨어지거나 잘못된 길이의 키로 기동되는 사고를 기동 시점에 막는지 본다.
 * 예외 메시지에는 프로퍼티 이름만 있고 키 값이 실려서는 안 된다.
 */
@DisplayName("PII DEK 공급자·운영 가드 테스트")
class PiiKeyProviderTest {

  private static String base64Of(String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("C19 키가 비어 있거나 null이면 생성자에서 즉시 실패한다")
  void rawProvider_blankKey_failsFast() {
    assertThatThrownBy(() -> new RawPiiDataKeyProvider(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.crypto.pii.key");
    assertThatThrownBy(() -> new RawPiiDataKeyProvider("   "))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new RawPiiDataKeyProvider(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("C20 base64 디코딩 결과가 32바이트가 아니면(31·33) 기동을 막는다")
  void rawProvider_wrongKeyLength_failsFast() {
    String shortKey = base64Of("a".repeat(31));
    String longKey = base64Of("a".repeat(33));

    assertThatThrownBy(() -> new RawPiiDataKeyProvider(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32바이트");
    assertThatThrownBy(() -> new RawPiiDataKeyProvider(longKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32바이트");
  }

  @Test
  @DisplayName("C20 base64가 아닌 값은 설정 오류로 정규화되고 예외 메시지에 키 값이 실리지 않는다")
  void rawProvider_invalidBase64_failsFastWithoutLeakingValue() {
    String notBase64 = "이건-base64가-아니다!!";

    assertThatThrownBy(() -> new RawPiiDataKeyProvider(notBase64))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base64")
        .hasMessageNotContaining(notBase64);
  }

  @Test
  @DisplayName("C21 raw 키 모드는 keyVersion 1만 해석하고 다른 버전은 IllegalStateException")
  void rawProvider_unknownKeyVersion_throws() {
    RawPiiDataKeyProvider provider = new RawPiiDataKeyProvider(base64Of("dev-local-pii-encryption-key-32b"));

    assertThat(provider.active().version()).isEqualTo(1);
    assertThat(provider.byVersion(1)).isSameAs(provider.active().key());
    assertThatThrownBy(() -> provider.byVersion(2)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.byVersion(0)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("C22 운영에서 kms-key-id가 비어 있으면 가드가 기동을 막는다")
  void prodGuard_missingKmsKeyId_throws() {
    assertThatThrownBy(() -> new PiiCryptoProdGuard("", "", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("kms-key-id");
  }

  @Test
  @DisplayName("C23 운영에서 raw key가 설정돼 있으면 kms-key-id가 있어도 기동을 막는다")
  void prodGuard_rawKeyPresent_throws() {
    String rawKey = base64Of("dev-local-pii-encryption-key-32b");

    assertThatThrownBy(() -> new PiiCryptoProdGuard("arn:aws:kms:ap-northeast-2:1:key/abc", rawKey, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.crypto.pii.key")
        .hasMessageNotContaining(rawKey);
  }

  @Test
  @DisplayName("이슈 #232 운영에서 raw hmac key가 설정돼 있으면 kms-key-id가 있어도 기동을 막는다")
  void prodGuard_rawHmacKeyPresent_throws() {
    String rawHmacKey = base64Of("dev-local-pii-hmac-index-key-32b");

    assertThatThrownBy(() -> new PiiCryptoProdGuard("arn:aws:kms:ap-northeast-2:1:key/abc", "", rawHmacKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.crypto.pii.hmac-key")
        .hasMessageNotContaining(rawHmacKey);
  }

  @Test
  @DisplayName("운영에서 kms-key-id만 설정되면 가드를 통과한다")
  void prodGuard_kmsOnly_passes() {
    assertThatCode(() -> new PiiCryptoProdGuard("arn:aws:kms:ap-northeast-2:1:key/abc", "", ""))
        .doesNotThrowAnyException();
  }
}
