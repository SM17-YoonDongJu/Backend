package com.soma.backend.infra.kms;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code encryption_keys} 메타 테이블에 접근하는 얇은 DAO. 저장하는 것은 <b>KMS CMK로 봉인된 wrapped DEK</b>
 * 뿐이며 평문 DEK는 이 테이블에도, 어떤 로그·응답에도 남기지 않는다.
 *
 * <p><b>native SQL을 쓰는 사유(하네스 '조회 native 금지' 규칙의 문서화된 예외):</b>
 * <ul>
 *   <li>{@code encryption_keys}는 도메인 Aggregate가 아니라 인프라 메타데이터이고, JPA 엔티티로 매핑하지
 *       않기로 결정했다(design.md §5.2). 매핑 대상 엔티티가 없으므로 JPQL·QueryDSL로 표현할 수 없다</li>
 *   <li>이 DAO는 {@code PiiCipher} → {@code PiiDataKeyProvider} 경로에서 쓰이는데, 이 경로는 Hibernate가
 *       {@code AttributeConverter}를 만드는 EntityManagerFactory 부트스트랩 도중에 함께 생성된다. 여기서
 *       Spring Data Repository를 쓰면 {@code EntityManagerFactory ↔ Repository} 순환으로 기동이 깨진다.
 *       {@link JdbcTemplate}은 {@code DataSource}에만 의존해 순환이 없다</li>
 *   <li>접근 패턴은 단순 조회 2종 + 조건부 INSERT 1종뿐이라 Spring Data가 줄 이점이 없다</li>
 * </ul>
 *
 * <p>모든 쿼리는 도메인 트랜잭션에 얹지 않고 {@link JdbcTemplate} 기본 동작(auto-commit)으로 실행한다 —
 * DEK 부트스트랩은 최초 암복호화 시점에 1회 일어나는 인프라 작업이라 도메인 트랜잭션의 커밋/롤백에
 * 묶이면 안 된다.
 */
public class EncryptionKeyStore {

  private static final String SELECT_ACTIVE =
      "SELECT key_version, wrapped_dek FROM encryption_keys WHERE purpose = ? AND is_active";
  private static final String SELECT_BY_VERSION =
      "SELECT wrapped_dek FROM encryption_keys WHERE purpose = ? AND key_version = ?";
  private static final String INSERT_ACTIVE =
      "INSERT INTO encryption_keys (purpose, key_version, wrapped_dek, kms_key_id, is_active) "
          + "VALUES (?, ?, ?, ?, true) ON CONFLICT DO NOTHING";

  private final JdbcTemplate jdbcTemplate;

  public EncryptionKeyStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 용도별 활성 키(버전 + wrapped DEK). 부분 유니크 인덱스가 활성 키 1개를 보장한다. */
  public Optional<WrappedKey> findActive(String purpose) {
    List<WrappedKey> rows = jdbcTemplate.query(
        SELECT_ACTIVE,
        (rs, rowNum) -> new WrappedKey(rs.getInt("key_version"), rs.getBytes("wrapped_dek")),
        purpose);
    return rows.stream().findFirst();
  }

  /** 봉투 헤더의 keyVersion으로 wrapped DEK를 찾는다. 로테이션 이후 구 버전 암호문 복호화에 쓰인다. */
  public Optional<byte[]> findWrappedDek(String purpose, int keyVersion) {
    List<byte[]> rows = jdbcTemplate.query(
        SELECT_BY_VERSION,
        (rs, rowNum) -> rs.getBytes("wrapped_dek"),
        purpose, keyVersion);
    return rows.stream().findFirst();
  }

  /**
   * 최초 활성 키를 등록한다. 다중 인스턴스가 동시에 기동하면 여러 인스턴스가 각자
   * {@code kms:GenerateDataKey}를 호출할 수 있는데, 유니크 인덱스 + {@code ON CONFLICT DO NOTHING}으로
   * 첫 INSERT만 살아남는다. 그래서 <b>호출자는 INSERT 후 반드시 재조회</b>해 실제로 저장된 행의 wrapped DEK를
   * 써야 한다(자기가 만든 평문 DEK를 그대로 쓰면 인스턴스마다 다른 키로 암호화하게 된다).
   *
   * @return 이 인스턴스의 INSERT가 실제로 반영됐으면 true(경쟁에서 이김)
   */
  public boolean insertActiveIfAbsent(String purpose, int keyVersion, byte[] wrappedDek, String kmsKeyId) {
    return jdbcTemplate.update(INSERT_ACTIVE, purpose, keyVersion, wrappedDek, kmsKeyId) > 0;
  }

  /** 조회된 키 메타 — 여기 담기는 것은 봉인된 DEK뿐이고 평문 DEK는 절대 담기지 않는다. */
  public record WrappedKey(int keyVersion, byte[] wrappedDek) {
  }
}
