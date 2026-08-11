package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * PiiCipher 경계 케이스 테스트 — design.md §12.1(C1~C11) 중 {@code PiiCipherSmokeTest}가 다루지 않는
 * 나머지 시나리오를 채운다(C4 테이블 간 AAD 교차, C5 위치별 변조 4종, C6 keyVersion 변조, C7 길이·version 오류,
 * C9 한글·이모지·64KB, C10 다른 키, C11 예외 메시지 유출).
 *
 * <p>모든 실패는 원인을 구분하지 않고 {@link ErrorCode#PII_CRYPTO_ERROR} 하나로 정규화돼야 한다
 * (복호화 오라클 표면 축소). 예외 메시지·스택트레이스에 평문·키·암호문이 실려서도 안 된다.
 */
@DisplayName("PiiCipher 경계 케이스 테스트")
class PiiCipherTest {

  private static final String DEV_KEY = base64Key("dev-local-pii-encryption-key-32b");
  private static final String OTHER_KEY = base64Key("another-pii-encryption-key-32byt");

  private final PiiCipher cipher = new PiiCipher(new RawPiiDataKeyProvider(DEV_KEY));
  private final PiiCipher otherKeyCipher = new PiiCipher(new RawPiiDataKeyProvider(OTHER_KEY));
  private final PiiAad claimAad = PiiAad.ofColumn("user_claims", "additional_information");

  private static String base64Key(String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("C4 컬럼명이 같아도 테이블이 다르면 복호화되지 않는다(reports ↔ report_reviews)")
  void decrypt_sameColumnDifferentTable_fails() {
    // Given — reports.applicable_guarantees로 봉인한 값
    PiiAad reportsAad = PiiAad.ofColumn("reports", "applicable_guarantees");
    PiiAad reviewsAad = PiiAad.ofColumn("report_reviews", "applicable_guarantees");
    byte[] envelope = cipher.encryptText("[\"상해후유장해\"]", reportsAad);

    // When & Then — 컬럼명이 같은 다른 테이블 AAD로는 열리지 않는다
    assertThatThrownBy(() -> cipher.decryptText(envelope, reviewsAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C4 테이블이 같아도 컬럼이 다르면 복호화되지 않는다(스코프 밖 컬럼으로 이식 차단)")
  void decrypt_sameTableDifferentColumn_fails() {
    PiiAad insurerAad = PiiAad.ofColumn("user_insurances", "insurer_name");
    PiiAad productAad = PiiAad.ofColumn("user_insurances", "product_name");
    byte[] envelope = cipher.encryptText("OO손해보험", insurerAad);

    assertThatThrownBy(() -> cipher.decryptText(envelope, productAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C5 암호문 1바이트 변조는 GCM 태그 검증에서 잡힌다")
  void decrypt_tamperedCiphertext_fails() {
    byte[] envelope = cipher.encryptText("증권번호 100-2024-558123", claimAad);
    envelope[PiiEnvelope.HEADER_LENGTH + PiiEnvelope.NONCE_LENGTH] ^= 0x01;

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C5 GCM 태그(마지막 바이트) 변조는 복호화에 실패한다")
  void decrypt_tamperedTag_fails() {
    byte[] envelope = cipher.encryptText("증권번호 100-2024-558123", claimAad);
    envelope[envelope.length - 1] ^= 0x01;

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C5 nonce 1바이트 변조는 복호화에 실패한다")
  void decrypt_tamperedNonce_fails() {
    byte[] envelope = cipher.encryptText("증권번호 100-2024-558123", claimAad);
    envelope[PiiEnvelope.HEADER_LENGTH] ^= 0x01;

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C5 aadScope 바이트를 행 바인딩(0x02)으로 올려도 다운그레이드 없이 거부된다")
  void decrypt_tamperedScope_fails() {
    byte[] envelope = cipher.encryptText("스코프 변조", claimAad);
    envelope[1] = PiiEnvelope.SCOPE_TABLE_COLUMN_ROW;

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C6 keyVersion 상위 바이트를 올려 다른 키 버전을 가리키게 해도 복호화되지 않는다")
  void decrypt_tamperedKeyVersion_fails() {
    byte[] envelope = cipher.encryptText("키 버전 변조", claimAad);
    // keyVersion = 1 → 0x0101(257). raw 키 모드는 버전 1만 알고 있으므로 provider 단계에서 먼저 걸린다.
    envelope[2] = 0x01;

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C7 빈 배열 봉투는 인덱스 초과 없이 PII_CRYPTO_ERROR로 거부된다")
  void decrypt_emptyEnvelope_fails() {
    assertThatThrownBy(() -> cipher.decryptText(new byte[0], claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C7 최소 길이보다 1바이트 짧은 봉투도 인덱스 초과 없이 거부된다")
  void decrypt_oneByteShorterThanMinimum_fails() {
    byte[] envelope = cipher.encryptText("", claimAad);
    assertThat(envelope).hasSize(PiiEnvelope.MIN_LENGTH);
    byte[] truncated = new byte[PiiEnvelope.MIN_LENGTH - 1];
    System.arraycopy(envelope, 0, truncated, 0, truncated.length);

    assertThatThrownBy(() -> cipher.decryptText(truncated, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C7 알 수 없는 포맷 version 바이트는 복호화 시도 전에 거부된다")
  void decrypt_unknownVersion_fails() {
    byte[] envelope = cipher.encryptText("알 수 없는 버전", claimAad);
    envelope[0] = 0x7F;

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C7 null 봉투·null AAD도 NPE가 아니라 PII_CRYPTO_ERROR로 거부된다")
  void decrypt_nullArguments_fails() {
    byte[] envelope = cipher.encryptText("널 방어", claimAad);

    assertThatThrownBy(() -> cipher.decryptText(null, claimAad))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> cipher.decryptText(envelope, null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> cipher.encryptText(null, claimAad))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("C9 한글·이모지·특수문자가 섞인 문자열도 정확히 왕복한다")
  void roundTrip_unicodeAndEmoji_succeeds() {
    String plaintext = "교통사고 후유증 😢 진단서 첨부, 특약 \"골절진단비\"\n줄바꿈\t탭 \\역슬래시";

    assertThat(cipher.decryptText(cipher.encryptText(plaintext, claimAad), claimAad)).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("C9 64KB 장문도 왕복하고 봉투는 평문보다 정확히 32바이트 크다")
  void roundTrip_64KbText_succeeds() {
    String plaintext = "가".repeat(64 * 1024);
    byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);

    byte[] envelope = cipher.encryptText(plaintext, claimAad);

    assertThat(envelope).hasSize(plainBytes.length + PiiEnvelope.MIN_LENGTH);
    assertThat(cipher.decryptText(envelope, claimAad)).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("C10 다른 DEK로는 같은 AAD라도 복호화되지 않는다")
  void decrypt_differentKey_fails() {
    byte[] envelope = cipher.encryptText("키가 다르면 못 연다", claimAad);

    assertThatThrownBy(() -> otherKeyCipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }

  @Test
  @DisplayName("C11 실패 예외의 메시지·스택트레이스에 평문·키·암호문이 실리지 않는다")
  void decrypt_failure_doesNotLeakSecrets() {
    String plaintext = "주민등록번호 900101-1234567";
    byte[] envelope = cipher.encryptText(plaintext, claimAad);
    envelope[envelope.length - 1] ^= 0x01;

    BusinessException thrown = catchPiiException(() -> cipher.decryptText(envelope, claimAad));

    String rendered = render(thrown);
    assertThat(rendered).doesNotContain(plaintext);
    assertThat(rendered).doesNotContain("900101");
    assertThat(rendered).doesNotContain(DEV_KEY);
    assertThat(rendered).doesNotContain(HexFormat.of().formatHex(envelope));
    assertThat(thrown).hasMessage(ErrorCode.PII_CRYPTO_ERROR.getMessage());
  }

  @Test
  @DisplayName("C11 활성 DEK를 문자열화해도 키 바이트가 노출되지 않는다")
  void activeDataKey_toString_masksKey() {
    PiiDataKeyProvider.ActiveDataKey active = new RawPiiDataKeyProvider(DEV_KEY).active();

    assertThat(active.toString()).contains("version=1").contains("***");
    assertThat(active.toString()).doesNotContain("dev-local-pii-encryption-key-32b");
  }

  private static BusinessException catchPiiException(Runnable runnable) {
    try {
      runnable.run();
    } catch (BusinessException ex) {
      return ex;
    }
    throw new AssertionError("BusinessException(PII_CRYPTO_ERROR)이 발생해야 합니다");
  }

  /** 예외 메시지 + 원인 체인 + 스택트레이스 프레임을 한 문자열로 펼쳐 유출 여부를 본다. */
  private static String render(Throwable throwable) {
    StringBuilder builder = new StringBuilder();
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      builder.append(current).append('\n');
      for (StackTraceElement element : current.getStackTrace()) {
        builder.append(element).append('\n');
      }
    }
    return builder.toString();
  }
}
