package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * PiiHmac(HMAC-SHA256 블라인드 인덱스) 단위 테스트(이슈 #232).
 *
 * <p>블라인드 인덱스가 조회 인덱스로 쓸모 있으려면 같은 평문·같은 (table, column)이면 항상 같은 다이제스트가
 * 나와야 하고(결정적), 컬럼·키가 다르면 값이 같아도 다이제스트가 달라야 한다(컬럼 간 재사용·무지개표 방지).
 */
@DisplayName("PiiHmac 테스트")
class PiiHmacTest {

  private static final String KEY = base64Key("dev-local-pii-hmac-index-key-32b");
  private static final String OTHER_KEY = base64Key("another-pii-hmac-index-key-32byt");

  private final PiiHmac hmac = new PiiHmac(new RawPiiDataKeyProvider(KEY));
  private final PiiHmac otherKeyHmac = new PiiHmac(new RawPiiDataKeyProvider(OTHER_KEY));
  private final PiiAad phoneAad = PiiAad.ofColumn("users", "phone_number");

  private static String base64Key(String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("같은 평문·같은 AAD는 항상 같은 다이제스트를 낸다(결정적)")
  void hmac_isDeterministic() {
    byte[] first = hmac.hmac("01012345678", phoneAad);
    byte[] second = hmac.hmac("01012345678", phoneAad);

    assertThat(first).isEqualTo(second);
    assertThat(first).hasSize(32);
  }

  @Test
  @DisplayName("평문이 다르면 다이제스트도 달라진다")
  void hmac_differentPlaintext_differentDigest() {
    byte[] first = hmac.hmac("01012345678", phoneAad);
    byte[] second = hmac.hmac("01012345679", phoneAad);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("같은 평문이라도 AAD(테이블·컬럼)가 다르면 다이제스트가 달라진다")
  void hmac_sameValueDifferentColumn_differentDigest() {
    PiiAad providerUserIdAad = PiiAad.ofColumn("social_accounts", "provider_user_id");

    byte[] asPhone = hmac.hmac("12345", phoneAad);
    byte[] asProviderUserId = hmac.hmac("12345", providerUserIdAad);

    assertThat(asPhone).isNotEqualTo(asProviderUserId);
  }

  @Test
  @DisplayName("키가 다르면 같은 입력이라도 다이제스트가 달라진다")
  void hmac_differentKey_differentDigest() {
    byte[] withKey = hmac.hmac("01012345678", phoneAad);
    byte[] withOtherKey = otherKeyHmac.hmac("01012345678", phoneAad);

    assertThat(withKey).isNotEqualTo(withOtherKey);
  }

  @Test
  @DisplayName("null 평문·null AAD는 NPE가 아니라 PII_CRYPTO_ERROR로 거부된다")
  void hmac_nullArguments_fails() {
    assertThatThrownBy(() -> hmac.hmac(null, phoneAad))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
    assertThatThrownBy(() -> hmac.hmac("01012345678", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.PII_CRYPTO_ERROR);
  }
}
