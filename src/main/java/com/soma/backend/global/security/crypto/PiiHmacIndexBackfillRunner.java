package com.soma.backend.global.security.crypto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code users.phone_number}·{@code social_accounts.provider_user_id} HMAC 블라인드 인덱스
 * expand-migrate-contract의 migrate 단계(이슈 #232, V37이 만든 {@code *_enc}/{@code *_hmac} 컬럼을 채운다).
 *
 * <p>AES-GCM 암호화·HMAC 다이제스트는 SQL로 계산할 수 없어(DEK가 앱 메모리·KMS에만 있음) 기동 시점에
 * 애플리케이션이 직접 채운다. 대상은 아직 채워지지 않은 행뿐이라({@code *_enc IS NULL}) 재기동해도
 * 이미 채운 행을 다시 건드리지 않는다(멱등). 기존 평문 컬럼은 그대로 두므로 백필 중에도 서비스는
 * 계속 옛 평문 컬럼으로 동작한다 — 컷오버(엔티티·리포지토리가 새 컬럼을 쓰도록 전환)는 contract 단계에서
 * 별도 배포로 수행한다.
 *
 * <p>contract 마이그레이션이 옛 평문 컬럼을 지우고 나면 이 러너가 참조하는 컬럼이 사라지므로,
 * contract 배포 시점에 이 클래스 자체를 제거한다.
 *
 * <p>{@code test} 프로파일에서는 빈 자체를 등록하지 않는다({@code @Profile("!test")}) — 테스트 DB는
 * {@code ddl-auto: create-drop}으로 JPA 엔티티 매핑에서만 스키마가 생성되는데, V37이 추가한
 * {@code *_enc}/{@code *_hmac} 컬럼은 의도적으로 어떤 엔티티에도 매핑하지 않아(순수 마이그레이션 임시
 * 컬럼) 테스트 스키마에는 애초에 존재하지 않는다.
 */
@Component
@Profile("!test")
public class PiiHmacIndexBackfillRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(PiiHmacIndexBackfillRunner.class);

  private final JdbcTemplate jdbcTemplate;
  private final PiiCipher piiCipher;
  private final PiiHmac piiHmac;
  private final boolean enabled;

  public PiiHmacIndexBackfillRunner(
      JdbcTemplate jdbcTemplate,
      PiiCipher piiCipher,
      PiiHmac piiHmac,
      @Value("${app.crypto.pii.hmac-backfill.enabled:true}") boolean enabled) {
    this.jdbcTemplate = jdbcTemplate;
    this.piiCipher = piiCipher;
    this.piiHmac = piiHmac;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    backfill("users", "id", "phone_number", "phone_number_enc", "phone_number_hmac");
    backfill("social_accounts", "id", "provider_user_id", "provider_user_id_enc", "provider_user_id_hmac");
  }

  private void backfill(String table, String idColumn, String plainColumn, String encColumn, String hmacColumn) {
    List<Map.Entry<UUID, String>> pending = jdbcTemplate.query(
        "SELECT " + idColumn + " AS id, " + plainColumn + " AS plain FROM " + table
            + " WHERE " + plainColumn + " IS NOT NULL AND " + encColumn + " IS NULL",
        (rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("plain")));
    if (pending.isEmpty()) {
      return;
    }
    PiiAad aad = PiiAad.ofColumn(table, plainColumn);
    String updateSql = "UPDATE " + table + " SET " + encColumn + " = ?, " + hmacColumn + " = ? WHERE " + idColumn
        + " = ?";
    for (Map.Entry<UUID, String> row : pending) {
      byte[] encrypted = piiCipher.encryptText(row.getValue(), aad);
      byte[] digest = piiHmac.hmac(row.getValue(), aad);
      jdbcTemplate.update(updateSql, encrypted, digest, row.getKey());
    }
    log.info("{}.{} HMAC 블라인드 인덱스 백필 완료 — {}건", table, plainColumn, pending.size());
  }
}
