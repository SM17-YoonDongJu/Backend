package com.soma.backend.global.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * PiiHmacIndexBackfillRunner 백필 로직 테스트(이슈 #232, V37 expand → runner migrate 단계).
 *
 * <p>{@code test} 프로파일에서는 {@code @Profile("!test")}라 빈이 등록되지 않으므로(V37이 만드는 임시
 * 컬럼이 엔티티에 없어 create-drop 스키마에 없다), 이 테스트는 실행 대상 컬럼을 직접 만들고 러너를 수동으로
 * 생성·호출해 백필 SQL 로직만 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("PII HMAC 블라인드 인덱스 백필 러너 테스트")
class PiiHmacIndexBackfillRunnerTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private PiiCipher piiCipher;

  @Autowired
  private PiiHmac piiHmac;

  @Autowired
  private UserRepository userRepository;

  @PersistenceContext
  private EntityManager entityManager;

  private PiiHmacIndexBackfillRunner runner;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number_enc bytea");
    jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number_hmac bytea");
    jdbcTemplate.execute("ALTER TABLE social_accounts ADD COLUMN IF NOT EXISTS provider_user_id_enc bytea");
    jdbcTemplate.execute("ALTER TABLE social_accounts ADD COLUMN IF NOT EXISTS provider_user_id_hmac bytea");
    runner = new PiiHmacIndexBackfillRunner(jdbcTemplate, piiCipher, piiHmac, true);
  }

  @Test
  @DisplayName("평문 phone_number가 있고 enc가 비어 있는 행을 암호화·HMAC 계산해 채운다")
  void run_backfillsPendingRows() {
    User user = userRepository.save(
        User.create("백필대상", LocalDate.of(1990, 1, 1), "male", "01099998888", Role.USER, null));
    entityManager.flush();

    runner.run(new DefaultApplicationArguments());

    byte[] enc = jdbcTemplate.queryForObject(
        "SELECT phone_number_enc FROM users WHERE id = ?", byte[].class, user.getId());
    byte[] hmacDigest = jdbcTemplate.queryForObject(
        "SELECT phone_number_hmac FROM users WHERE id = ?", byte[].class, user.getId());

    assertThat(enc).isNotNull();
    assertThat(hmacDigest).isNotNull();
    PiiAad aad = PiiAad.ofColumn("users", "phone_number");
    assertThat(piiCipher.decryptText(enc, aad)).isEqualTo("01099998888");
    assertThat(hmacDigest).isEqualTo(piiHmac.hmac("01099998888", aad));
  }

  @Test
  @DisplayName("이미 채워진 행은 다시 건드리지 않는다(멱등)")
  void run_skipsAlreadyBackfilledRows() {
    User user = userRepository.save(
        User.create("멱등대상", LocalDate.of(1991, 2, 2), "female", "01011112222", Role.USER, null));
    entityManager.flush();
    runner.run(new DefaultApplicationArguments());
    byte[] before = jdbcTemplate.queryForObject(
        "SELECT phone_number_enc FROM users WHERE id = ?", byte[].class, user.getId());

    runner.run(new DefaultApplicationArguments());
    byte[] after = jdbcTemplate.queryForObject(
        "SELECT phone_number_enc FROM users WHERE id = ?", byte[].class, user.getId());

    assertThat(after).isEqualTo(before);
  }

  @Test
  @DisplayName("phone_number가 null인 행은 대상에서 제외된다")
  void run_skipsNullPhoneNumber() {
    User user = userRepository.save(
        User.create("번호없음", LocalDate.of(1992, 3, 3), "male", null, Role.USER, null));
    entityManager.flush();

    runner.run(new DefaultApplicationArguments());

    byte[] enc = jdbcTemplate.queryForObject(
        "SELECT phone_number_enc FROM users WHERE id = ?", byte[].class, user.getId());
    assertThat(enc).isNull();
  }

  @Test
  @DisplayName("enabled=false면 대상 행이 있어도 아무것도 채우지 않는다")
  void run_disabled_doesNothing() {
    User user = userRepository.save(
        User.create("비활성화", LocalDate.of(1993, 4, 4), "female", "01033334444", Role.USER, null));
    entityManager.flush();
    PiiHmacIndexBackfillRunner disabledRunner =
        new PiiHmacIndexBackfillRunner(jdbcTemplate, piiCipher, piiHmac, false);

    disabledRunner.run(new DefaultApplicationArguments());

    List<byte[]> enc = jdbcTemplate.query(
        "SELECT phone_number_enc FROM users WHERE id = ?", (rs, rowNum) -> rs.getBytes(1), user.getId());
    assertThat(enc).hasSize(1);
    assertThat(enc.get(0)).isNull();
  }
}
