package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;

/**
 * PiiCipher 스모크 테스트 — 봉투 왕복·AAD 바인딩·변조 탐지의 최소 보장만 확인한다.
 * design.md §12.1의 전체 시나리오(C1~C11)는 qa-reviewer가 별도 테스트로 채운다.
 */
@DisplayName("PiiCipher 스모크 테스트")
class PiiCipherSmokeTest {

  private static final String DEV_KEY = Base64.getEncoder()
      .encodeToString("dev-local-pii-encryption-key-32b".getBytes(StandardCharsets.UTF_8));

  private final PiiCipher cipher = new PiiCipher(new RawPiiDataKeyProvider(DEV_KEY));
  private final PiiAad claimAad = PiiAad.ofColumn("user_claims", "additional_information");
  private final PiiAad policyAad = PiiAad.ofColumn("user_insurances", "policy_no");

  @Test
  @DisplayName("암호화한 봉투는 평문을 담지 않고 같은 AAD로 원문 복원된다")
  void roundTrip_sameAad_restoresPlaintext() {
    byte[] envelope = cipher.encryptText("보험금 청구 부가설명", claimAad);

    assertThat(new String(envelope, StandardCharsets.UTF_8)).doesNotContain("보험금");
    assertThat(cipher.decryptText(envelope, claimAad)).isEqualTo("보험금 청구 부가설명");
  }

  @Test
  @DisplayName("같은 평문도 매번 다른 암호문이 된다(nonce 랜덤)")
  void encrypt_samePlaintext_producesDifferentEnvelopes() {
    assertThat(cipher.encryptText("동일값", claimAad)).isNotEqualTo(cipher.encryptText("동일값", claimAad));
  }

  @Test
  @DisplayName("다른 컬럼의 AAD로는 복호화되지 않는다(컬럼 간 암호문 치환 차단)")
  void decrypt_differentAad_fails() {
    byte[] envelope = cipher.encryptText("컬럼 치환 시도", claimAad);

    assertThatThrownBy(() -> cipher.decryptText(envelope, policyAad))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("헤더(keyVersion) 변조는 GCM 태그 검증에서 잡힌다")
  void decrypt_tamperedHeader_fails() {
    byte[] envelope = cipher.encryptText("헤더 변조", claimAad);
    envelope[3] = (byte) (envelope[3] ^ 0x01);

    assertThatThrownBy(() -> cipher.decryptText(envelope, claimAad))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("최소 길이에 못 미치는 봉투는 인덱스 예외 없이 거부된다")
  void decrypt_tooShortEnvelope_failsWithoutIndexError() {
    assertThatThrownBy(() -> cipher.decryptText(new byte[] {0x01, 0x01}, claimAad))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("빈 문자열도 왕복한다(null과 구분)")
  void roundTrip_emptyString_succeeds() {
    assertThat(cipher.decryptText(cipher.encryptText("", claimAad), claimAad)).isEmpty();
  }
}
