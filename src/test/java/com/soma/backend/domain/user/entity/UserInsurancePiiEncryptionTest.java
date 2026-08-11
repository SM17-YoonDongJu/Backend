package com.soma.backend.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.user.repository.UserInsuranceRepository;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.crypto.PiiEnvelope;

/**
 * USER_INSURANCES 5개 PII 컬럼(보험사명·상품명·증권번호·가입일·담보) 암호화 통합 검증(design.md §12.4 C26).
 *
 * <p>엔티티 왕복과 함께 {@link JdbcTemplate}으로 raw 컬럼을 직접 읽어 실제 봉투(bytea)로 저장됐는지 확인한다.
 * {@code coverages}는 {@code text[]}가 아니라 JSON 배열을 통째로 암호화한 단일 bytea여야 한다(§6).
 *
 * <p>{@code UserInsurance}는 쓰기 경로(POST /users/me/insurances)가 아직 없는 읽기 모델이라 팩터리가 없다 —
 * 테스트에서는 리플렉션으로 필드를 채워 영속화한다. {@code created_at}은 운영 스키마의 DB DEFAULT(now())가
 * 채우는데 테스트 프로파일은 엔티티 매핑으로 스키마를 생성(ddl-auto=create-drop)해 기본값이 없으므로,
 * 삽입 전에 기본값을 보강한다(test_db 한정 보정, 운영 스키마는 V27/V28이 이미 DEFAULT를 갖는다).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("USER_INSURANCES PII 컬럼 암호화 통합 테스트")
class UserInsurancePiiEncryptionTest {

  private static final String INSURER_NAME = "OO손해보험";
  private static final String PRODUCT_NAME = "무배당 행복드림 종합보험";
  private static final String POLICY_NO = "100-2024-558123";
  private static final LocalDate ENROLLED_AT = LocalDate.of(2024, 3, 15);
  private static final List<String> COVERAGES = List.of("상해후유장해", "골절진단비", "입원일당");

  @Autowired
  private UserInsuranceRepository userInsuranceRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  private UUID persistInsurance(String policyNo, LocalDate enrolledAt, List<String> coverages) {
    jdbcTemplate.execute(UserInsuranceFixture.createdAtDefaultDdl());
    User user = userRepository.save(
        User.create("보험암호화유저", LocalDate.of(1990, 1, 1), "", null, Role.USER, null));
    UserInsurance saved = userInsuranceRepository.save(UserInsuranceFixture.of(
        user.getId(), INSURER_NAME, PRODUCT_NAME, policyNo, enrolledAt, coverages, null));
    entityManager.flush();
    entityManager.clear();
    return saved.getId();
  }

  @Test
  @DisplayName("C26 5개 필드 전부 저장 후 재조회하면 원문과 담보 순서·개수가 그대로 복원된다")
  void allPiiFields_roundTripThroughConverters() {
    UUID id = persistInsurance(POLICY_NO, ENROLLED_AT, COVERAGES);

    UserInsurance found = userInsuranceRepository.findById(id).orElseThrow();

    assertThat(found.getInsurerName()).isEqualTo(INSURER_NAME);
    assertThat(found.getProductName()).isEqualTo(PRODUCT_NAME);
    assertThat(found.getPolicyNo()).isEqualTo(POLICY_NO);
    assertThat(found.getEnrolledAt()).isEqualTo(ENROLLED_AT);
    assertThat(found.getCoverages()).containsExactly("상해후유장해", "골절진단비", "입원일당");
  }

  @Test
  @DisplayName("C26 raw 컬럼 5개가 모두 봉투(bytea)로 저장되고 평문 부분문자열이 남지 않는다")
  void allPiiColumns_areStoredAsEncryptedEnvelopes() {
    UUID id = persistInsurance(POLICY_NO, ENROLLED_AT, COVERAGES);

    Map<String, Object> row = jdbcTemplate.queryForMap(
        "SELECT insurer_name, product_name, policy_no, enrolled_at, coverages "
            + "FROM user_insurances WHERE id = ?", id);

    assertEncryptedEnvelope(row.get("insurer_name"), INSURER_NAME);
    assertEncryptedEnvelope(row.get("product_name"), PRODUCT_NAME);
    assertEncryptedEnvelope(row.get("policy_no"), POLICY_NO);
    assertEncryptedEnvelope(row.get("enrolled_at"), "2024-03-15");
    assertEncryptedEnvelope(row.get("coverages"), "상해후유장해");
  }

  @Test
  @DisplayName("C26 coverages는 text[]가 아니라 단일 bytea 컬럼이다(봉투 1개로 통째 암호화)")
  void coverages_isSingleByteaColumn() {
    persistInsurance(POLICY_NO, ENROLLED_AT, COVERAGES);

    String dataType = jdbcTemplate.queryForObject(
        "SELECT data_type FROM information_schema.columns "
            + "WHERE table_name = 'user_insurances' AND column_name = 'coverages'", String.class);

    assertThat(dataType).isEqualTo("bytea");
  }

  @Test
  @DisplayName("C13 빈 담보 목록은 null 컬럼이 아니라 봉투로 저장되고 빈 리스트로 복원된다")
  void coverages_emptyList_isDistinguishedFromNull() {
    UUID id = persistInsurance(POLICY_NO, ENROLLED_AT, List.of());

    byte[] stored = jdbcTemplate.queryForObject(
        "SELECT coverages FROM user_insurances WHERE id = ?", byte[].class, id);

    assertThat(stored).isNotNull();
    assertThat(userInsuranceRepository.findById(id).orElseThrow().getCoverages()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("C12 nullable PII 필드(policy_no·enrolled_at·coverages)가 null이면 컬럼도 null로 남는다")
  void nullablePiiFields_null_staysNull() {
    UUID id = persistInsurance(null, null, null);

    Map<String, Object> row = jdbcTemplate.queryForMap(
        "SELECT policy_no, enrolled_at, coverages FROM user_insurances WHERE id = ?", id);

    assertThat(row.get("policy_no")).isNull();
    assertThat(row.get("enrolled_at")).isNull();
    assertThat(row.get("coverages")).isNull();
    UserInsurance found = userInsuranceRepository.findById(id).orElseThrow();
    assertThat(found.getPolicyNo()).isNull();
    assertThat(found.getEnrolledAt()).isNull();
    assertThat(found.getCoverages()).isNull();
  }

  @Test
  @DisplayName("C14 담보 원소에 null이 섞여 있어도 저장·재조회가 NPE 없이 성공한다")
  void coverages_withNullElement_roundTrips() {
    UUID id = persistInsurance(POLICY_NO, ENROLLED_AT, Arrays.asList("상해후유장해", null));

    assertThat(userInsuranceRepository.findById(id).orElseThrow().getCoverages())
        .containsExactly("상해후유장해", null);
  }

  private void assertEncryptedEnvelope(Object column, String plaintextFragment) {
    assertThat(column).isInstanceOf(byte[].class);
    byte[] bytes = (byte[]) column;
    assertThat(bytes.length).isGreaterThanOrEqualTo(PiiEnvelope.MIN_LENGTH);
    assertThat(bytes[0]).isEqualTo(PiiEnvelope.VERSION_1);
    assertThat(bytes[1]).isEqualTo(PiiEnvelope.SCOPE_TABLE_COLUMN);
    assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain(plaintextFragment);
  }
}
