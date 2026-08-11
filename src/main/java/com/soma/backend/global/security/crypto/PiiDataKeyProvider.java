package com.soma.backend.global.security.crypto;

import javax.crypto.SecretKey;

/**
 * PII 봉투암호화에 쓸 DEK(Data Encryption Key) 공급 추상화. {@link PiiCipher}는 키가 어디서 오는지
 * (raw 프로퍼티 / KMS 봉투암호화) 알지 못하고 이 인터페이스만 본다.
 *
 * <p>구현은 두 가지다.
 * <ul>
 *   <li>{@code RawPiiDataKeyProvider} — 로컬·개발·테스트. {@code app.crypto.pii.key}의 base64 32바이트를
 *       그대로 DEK로 쓴다. KMS도 {@code encryption_keys} 테이블도 건드리지 않는다</li>
 *   <li>{@code KmsEnvelopePiiDataKeyProvider} — 운영. {@code encryption_keys}에 저장된 wrapped DEK를
 *       {@code kms:Decrypt}로 풀어 메모리에만 캐시한다</li>
 * </ul>
 *
 * <p><b>구현 규약:</b> 키 해석은 반드시 <b>지연(lazy)</b>이어야 한다. 컨버터는 Spring이 EntityManagerFactory를
 * 부트스트랩하는 도중 생성하므로, 생성자에서 DB·KMS를 호출하면 부트스트랩 순환이나 기동 지연을 만든다
 * (design.md §4.4). 또한 평문 DEK는 메모리 밖으로 나가서는 안 된다 — 로그·응답·디스크 어디에도 쓰지 않는다.
 */
public interface PiiDataKeyProvider {

  /** 암호화에 쓸 현재 활성 DEK(버전 포함). 최초 호출 시 1회 해석하고 이후 메모리에 캐시한다. */
  ActiveDataKey active();

  /**
   * 복호화용 — 봉투 헤더에 기록된 {@code keyVersion}에 해당하는 DEK를 반환한다.
   *
   * @throws IllegalStateException 해당 버전의 키를 찾을 수 없을 때(미등록 버전·헤더 변조).
   *     호출자({@link PiiCipher})가 {@code PII_CRYPTO_ERROR}로 정규화한다
   */
  SecretKey byVersion(int keyVersion);

  /** 활성 DEK와 그 버전. 평문 키는 메모리에만 존재하며 문자열화·로깅 대상이 아니다. */
  record ActiveDataKey(int version, SecretKey key) {

    /** 로그·디버거·예외 메시지에 평문 DEK가 새지 않도록 키 자체는 절대 문자열로 만들지 않는다. */
    @Override
    public String toString() {
      return "ActiveDataKey[version=" + version + ", key=***]";
    }
  }
}
