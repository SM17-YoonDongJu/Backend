package com.soma.backend.global.security.crypto;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 로컬·개발·테스트 전용 DEK 공급자. {@code app.crypto.pii.key}(base64 32바이트)를 그대로 DEK로 사용한다.
 *
 * <p><b>KMS도 {@code encryption_keys} 테이블도 전혀 건드리지 않는다.</b> 덕분에 테스트 프로파일
 * ({@code flyway.enabled: false} + {@code ddl-auto: create-drop})에서 메타 테이블이 없어도 동작하고,
 * 로컬은 {@code docker compose up -d}만으로 암복호화가 실제로 걸린 상태로 기동된다(평문 저장 경로가
 * 개발 중에만 살아 있다가 운영에서 처음 켜지는 사고를 막는다).
 *
 * <p>키 미설정은 {@link AesGcmCipher}와 동일하게 생성자에서 {@link IllegalStateException}으로 즉시
 * 실패시킨다(fail-fast). 운영에서 이 구현이 선택되는 것 자체를 막는 것은 {@link PiiCryptoProdGuard}의 몫이다.
 */
public class RawPiiDataKeyProvider implements PiiDataKeyProvider {

  /** raw key 경로의 고정 키 버전 — KMS 경로(1부터 증가)와 봉투 호환을 위해 1로 맞춘다. */
  private static final int RAW_KEY_VERSION = 1;
  private static final int KEY_LENGTH_BYTES = 32;
  private static final String KEY_ALGORITHM = "AES";

  private final ActiveDataKey active;

  public RawPiiDataKeyProvider(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new IllegalStateException(
          "app.crypto.pii.key(PII_ENC_KEY)가 설정되지 않았습니다 — 운영은 app.crypto.pii.kms-key-id를 쓰세요");
    }
    byte[] key = decode(base64Key);
    if (key.length != KEY_LENGTH_BYTES) {
      throw new IllegalStateException("app.crypto.pii.key는 base64 디코딩 시 32바이트(AES-256)여야 합니다");
    }
    this.active = new ActiveDataKey(RAW_KEY_VERSION, new SecretKeySpec(key, KEY_ALGORITHM));
  }

  @Override
  public ActiveDataKey active() {
    return active;
  }

  @Override
  public SecretKey byVersion(int keyVersion) {
    if (keyVersion != RAW_KEY_VERSION) {
      throw new IllegalStateException("raw 키 모드에서는 keyVersion=" + RAW_KEY_VERSION + "만 지원합니다");
    }
    return active.key();
  }

  /** base64 형식 오류를 설정 오류로 정규화한다 — 예외 메시지에 키 값을 싣지 않는다. */
  private static byte[] decode(String base64Key) {
    try {
      return Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("app.crypto.pii.key가 올바른 base64가 아닙니다");
    }
  }
}
