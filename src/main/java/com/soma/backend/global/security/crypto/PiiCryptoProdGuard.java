package com.soma.backend.global.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 운영 프로파일에서 PII 암호화 설정이 잘못된 채로 기동되는 것을 막는 fail-fast 가드.
 *
 * <p>{@link PiiCryptoConfig}는 프로퍼티 유무로 구현체를 고르기 때문에, 운영에서 {@code kms-key-id}를
 * 빠뜨리면 아무 소리 없이 raw 키 경로로 떨어질 수 있다. 그 경우 DEK가 환경변수(또는 yml 기본값)에 평문으로
 * 존재하게 되어 봉투암호화의 목적 자체가 사라진다. 이 빈이 그 두 가지 사고를 기동 시점에 예외로 드러낸다.
 *
 * <ul>
 *   <li>{@code app.crypto.pii.kms-key-id} 미설정 → 기동 차단(KMS 경로 미구성)</li>
 *   <li>{@code app.crypto.pii.key} 설정됨 → 기동 차단(운영에서 raw DEK 사용 금지)</li>
 *   <li>{@code app.crypto.pii.hmac-key} 설정됨 → 기동 차단(HMAC 블라인드 인덱스도 raw DEK 사용 금지)</li>
 * </ul>
 *
 * <p>예외 메시지에는 프로퍼티 <b>이름</b>만 넣고 값은 절대 넣지 않는다.
 */
@Component
@Profile("prod")
public class PiiCryptoProdGuard {

  public PiiCryptoProdGuard(@Value("${app.crypto.pii.kms-key-id:}") String kmsKeyId,
      @Value("${app.crypto.pii.key:}") String rawKey,
      @Value("${app.crypto.pii.hmac-key:}") String rawHmacKey) {
    if (!StringUtils.hasText(kmsKeyId)) {
      throw new IllegalStateException(
          "운영은 app.crypto.pii.kms-key-id(PII_KMS_KEY_ID)가 필수입니다 — KMS 봉투암호화 경로 미설정");
    }
    if (StringUtils.hasText(rawKey)) {
      throw new IllegalStateException(
          "운영에서 app.crypto.pii.key(PII_ENC_KEY) 사용은 금지입니다 — 값을 비우고 KMS 경로만 쓰세요");
    }
    if (StringUtils.hasText(rawHmacKey)) {
      throw new IllegalStateException(
          "운영에서 app.crypto.pii.hmac-key(PII_HMAC_KEY) 사용은 금지입니다 — 값을 비우고 KMS 경로만 쓰세요");
    }
  }
}
