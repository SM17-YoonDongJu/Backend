package com.soma.backend.infra.kms;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

import com.soma.backend.global.security.crypto.PiiDataKeyProvider;

/**
 * 운영 전용 DEK 공급자 — KMS 봉투암호화(envelope encryption) 경로.
 *
 * <p>평문 DEK는 DB에 저장하지 않는다. {@code encryption_keys}에는 CMK로 봉인된 wrapped DEK만 두고, 앱이
 * {@code kms:Decrypt}로 풀어 <b>메모리에만</b> 캐시한다. RDS 스냅샷·덤프가 유출돼도 CMK 접근 권한 없이는
 * 컬럼 암호문을 복호화할 수 없다.
 *
 * <p><b>지연(lazy) 부트스트랩이 필수인 이유:</b> 이 provider는 {@code PiiCipher} → {@code AttributeConverter}
 * 경로에서 쓰이고, 컨버터는 Hibernate가 EntityManagerFactory를 부트스트랩하는 도중 생성된다. 생성자에서
 * DB·KMS를 호출하면 기동이 지연되거나 순환이 생기므로, 첫 암복호화 시점에 이중 체크 락킹으로 1회만
 * 해석한다(design.md §4.4).
 *
 * <p><b>다중 인스턴스 최초 발급 경쟁:</b> 여러 인스턴스가 동시에 기동하면 각자 {@code GenerateDataKey}를
 * 호출할 수 있다. INSERT는 유니크 인덱스 + {@code ON CONFLICT DO NOTHING}으로 하나만 살아남고, 모든
 * 인스턴스는 INSERT 직후 <b>재조회한 행</b>의 wrapped DEK를 풀어 쓴다. 자기가 만든 평문 DEK를 그대로 쓰면
 * 인스턴스마다 다른 키로 암호화해 데이터가 파손된다.
 */
public class KmsEnvelopePiiDataKeyProvider implements PiiDataKeyProvider, DisposableBean {

  private static final int FIRST_KEY_VERSION = 1;
  private static final String KEY_ALGORITHM = "AES";

  private static final Logger log = LoggerFactory.getLogger(KmsEnvelopePiiDataKeyProvider.class);

  private final KmsClient kmsClient;
  private final String cmkId;
  private final EncryptionKeyStore keyStore;
  /** 이 provider 인스턴스가 관리하는 키 용도(예: {@code PII}·{@code PII_HMAC}). 같은 CMK를 공유해도 용도별로 별도 DEK를 쓴다. */
  private final String purpose;
  private final Map<Integer, SecretKey> cache = new ConcurrentHashMap<>();

  /** 활성 DEK. 첫 사용 시점에 한 번만 해석한다(이중 체크 락킹). */
  private volatile ActiveDataKey active;

  public KmsEnvelopePiiDataKeyProvider(KmsClient kmsClient, String cmkId, EncryptionKeyStore keyStore, String purpose) {
    this.kmsClient = kmsClient;
    this.cmkId = cmkId;
    this.keyStore = keyStore;
    this.purpose = purpose;
  }

  @Override
  public ActiveDataKey active() {
    ActiveDataKey resolved = this.active;
    if (resolved == null) {
      synchronized (this) {
        resolved = this.active;
        if (resolved == null) {
          resolved = bootstrap();
          this.active = resolved;
        }
      }
    }
    return resolved;
  }

  @Override
  public SecretKey byVersion(int keyVersion) {
    SecretKey cached = cache.get(keyVersion);
    if (cached != null) {
      return cached;
    }
    byte[] wrapped = keyStore.findWrappedDek(purpose, keyVersion)
        .orElseThrow(() -> new IllegalStateException(
            "요청한 키 버전의 DEK가 encryption_keys에 없습니다 — keyVersion=" + keyVersion));
    SecretKey key = unwrap(wrapped);
    SecretKey previous = cache.putIfAbsent(keyVersion, key);
    return previous != null ? previous : key;
  }

  /** KMS 클라이언트는 이 빈이 소유하므로 컨텍스트 종료 시 함께 닫는다. */
  @Override
  public void destroy() {
    kmsClient.close();
  }

  /**
   * 활성 DEK를 해석한다. 활성 행이 없으면 CMK로 최초 DEK를 발급해 등록하고, <b>등록 결과와 무관하게 다시
   * 조회</b>해 실제 저장된 wrapped DEK를 푼다.
   */
  private ActiveDataKey bootstrap() {
    Optional<EncryptionKeyStore.WrappedKey> found = keyStore.findActive(purpose);
    if (found.isEmpty()) {
      issueFirstKey();
      found = keyStore.findActive(purpose);
    }
    EncryptionKeyStore.WrappedKey row = found.orElseThrow(() -> new IllegalStateException(
        "PII 활성 DEK를 확보하지 못했습니다 — encryption_keys 등록·조회 실패"));

    SecretKey key = unwrap(row.wrappedDek());
    cache.put(row.keyVersion(), key);
    // 값·키는 남기지 않고 버전만 남긴다(운영 추적용).
    log.info("PII DEK 부트스트랩 완료 — keyVersion={}", row.keyVersion());
    return new ActiveDataKey(row.keyVersion(), key);
  }

  /**
   * CMK로 최초 DEK를 발급해 {@code encryption_keys}에 등록한다. 응답의 평문 DEK는 <b>사용하지 않고</b>
   * 즉시 0으로 덮어쓴다 — 경쟁에서 졌을 경우 다른 인스턴스가 등록한 키를 써야 하므로, 실제 사용 키는
   * 항상 재조회 → {@code kms:Decrypt} 경로로만 얻는다.
   */
  private void issueFirstKey() {
    GenerateDataKeyResponse response = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
        .keyId(cmkId)
        .keySpec(DataKeySpec.AES_256)
        .build());
    byte[] plaintextDek = response.plaintext().asByteArray();
    try {
      boolean inserted = keyStore.insertActiveIfAbsent(
          purpose, FIRST_KEY_VERSION, response.ciphertextBlob().asByteArray(), cmkId);
      log.info("PII DEK 최초 발급 시도 — keyVersion={}, inserted={}", FIRST_KEY_VERSION, inserted);
    } finally {
      Arrays.fill(plaintextDek, (byte) 0);
    }
  }

  /** wrapped DEK를 {@code kms:Decrypt}로 풀어 {@link SecretKey}로 만든다. 평문 바이트는 즉시 소거한다. */
  private SecretKey unwrap(byte[] wrappedDek) {
    byte[] plaintextDek = kmsClient.decrypt(DecryptRequest.builder()
            .keyId(cmkId)
            .ciphertextBlob(SdkBytes.fromByteArray(wrappedDek))
            .build())
        .plaintext()
        .asByteArray();
    try {
      return new SecretKeySpec(plaintextDek, KEY_ALGORITHM);
    } finally {
      // SecretKeySpec 생성자가 바이트를 복사하므로, 원본은 여기서 즉시 0으로 덮어써 힙 잔존을 줄인다.
      Arrays.fill(plaintextDek, (byte) 0);
    }
  }
}
