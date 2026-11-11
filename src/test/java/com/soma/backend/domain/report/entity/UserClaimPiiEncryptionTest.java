package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
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

import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.crypto.PiiEnvelope;

/**
 * USER_CLAIMS.additional_information PII 암호화 통합 검증(design.md §12.4 C24·C25).
 *
 * <p>엔티티 왕복(C24)만으로는 "컨버터가 실제로 동작하는지"를 알 수 없다 — 컨버터가 배선되지 않아 평문이
 * 그대로 저장돼도 왕복은 성공하기 때문이다. 그래서 {@link JdbcTemplate}으로 raw 컬럼을 직접 읽어
 * 봉투 헤더가 붙어 있고 평문 부분문자열이 남지 않았는지까지 확인한다(C25).
 *
 * <p>실제 test_db(PostgreSQL)를 쓰며 {@code @Transactional}로 종료 시 롤백한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("USER_CLAIMS PII 컬럼 암호화 통합 테스트")
class UserClaimPiiEncryptionTest {

  private static final String ADDITIONAL_INFORMATION =
      "보험사가 안내한 금액과 달라 추가로 설명드립니다. 연락처는 010-1234-5678 입니다.";

  @Autowired
  private UserClaimRepository userClaimRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  private UUID persistClaim(String additionalInformation) {
    User user = userRepository.save(
        User.create("암호화테스트유저", LocalDate.of(1995, 5, 5), "", null, null, Role.USER, null));
    ClaimDetails details = new MedicalIndemnityDetails(
        List.of("급성 충수염"), List.of(), List.of("입원"), List.of("2026-01-10"), "included", "2026-01-02", null);
    UserClaim saved = userClaimRepository.save(UserClaim.create(
        user.getId(), null, 1_000_000, LocalDate.of(2026, 1, 1),
        AccidentType.MEDICAL_INDEMNITY, details, "질문", "사고 경위", additionalInformation));
    entityManager.flush();
    entityManager.clear();
    return saved.getId();
  }

  @Test
  @DisplayName("C24 additional_information은 저장 후 재조회하면 원문 그대로 복호화된다")
  void additionalInformation_roundTripsThroughConverter() {
    UUID claimId = persistClaim(ADDITIONAL_INFORMATION);

    UserClaim found = userClaimRepository.findById(claimId).orElseThrow();

    assertThat(found.getAdditionalInformation()).isEqualTo(ADDITIONAL_INFORMATION);
    // description·question(이슈 #228)도 암호화 대상이지만, 엔티티 경유 조회라 컨버터가 투명하게
    // 복호화한다 — 값 자체는 그대로 왕복된다.
    assertThat(found.getDescription()).isEqualTo("사고 경위");
    assertThat(found.getQuestion()).isEqualTo("질문");
  }

  @Test
  @DisplayName("C25 raw 컬럼을 직접 읽으면 봉투 바이트이고 평문 부분문자열이 남아 있지 않다")
  void additionalInformation_isStoredAsEncryptedEnvelope() {
    UUID claimId = persistClaim(ADDITIONAL_INFORMATION);

    byte[] stored = jdbcTemplate.queryForObject(
        "SELECT additional_information FROM user_claims WHERE id = ?", byte[].class, claimId);

    assertThat(stored).isNotNull();
    assertThat(stored.length).isGreaterThanOrEqualTo(PiiEnvelope.MIN_LENGTH);
    assertThat(stored[0]).isEqualTo(PiiEnvelope.VERSION_1);
    assertThat(stored[1]).isEqualTo(PiiEnvelope.SCOPE_TABLE_COLUMN);
    String asText = new String(stored, StandardCharsets.UTF_8);
    assertThat(asText).doesNotContain(ADDITIONAL_INFORMATION);
    assertThat(asText).doesNotContain("010-1234-5678");
    assertThat(asText).doesNotContain("보험사가");
    // 평문 바이트 시퀀스 자체가 통째로 들어 있지 않은지도 확인한다.
    assertThat(contains(stored, ADDITIONAL_INFORMATION.getBytes(StandardCharsets.UTF_8))).isFalse();
  }

  @Test
  @DisplayName("이슈 #228 description·question도 raw 컬럼을 직접 읽으면 봉투 바이트이고 평문이 남지 않는다")
  void descriptionAndQuestion_areStoredAsEncryptedEnvelope() {
    UUID claimId = persistClaim(ADDITIONAL_INFORMATION);

    byte[] storedDescription = jdbcTemplate.queryForObject(
        "SELECT description FROM user_claims WHERE id = ?", byte[].class, claimId);
    byte[] storedQuestion = jdbcTemplate.queryForObject(
        "SELECT question FROM user_claims WHERE id = ?", byte[].class, claimId);

    assertThat(storedDescription).isNotNull();
    assertThat(storedDescription[0]).isEqualTo(PiiEnvelope.VERSION_1);
    assertThat(new String(storedDescription, StandardCharsets.UTF_8)).doesNotContain("사고 경위");

    assertThat(storedQuestion).isNotNull();
    assertThat(storedQuestion[0]).isEqualTo(PiiEnvelope.VERSION_1);
    assertThat(new String(storedQuestion, StandardCharsets.UTF_8)).doesNotContain("질문");
  }

  @Test
  @DisplayName("§12.5 조회만 한 뒤 flush해도 재암호화 UPDATE가 발생하지 않는다(암호문 불변)")
  void readOnlyLoad_doesNotRewriteCiphertext() {
    UUID claimId = persistClaim(ADDITIONAL_INFORMATION);
    byte[] before = jdbcTemplate.queryForObject(
        "SELECT additional_information FROM user_claims WHERE id = ?", byte[].class, claimId);

    // 값을 바꾸지 않고 로드만 한 뒤 flush — GCM은 매번 nonce가 달라, dirty-check가 잘못 걸리면
    // 조회만으로도 UPDATE가 나가 암호문이 매번 바뀐다(design.md §3.3-3b가 경고한 실패 모드).
    UserClaim loaded = userClaimRepository.findById(claimId).orElseThrow();
    assertThat(loaded.getAdditionalInformation()).isEqualTo(ADDITIONAL_INFORMATION);
    entityManager.flush();

    byte[] after = jdbcTemplate.queryForObject(
        "SELECT additional_information FROM user_claims WHERE id = ?", byte[].class, claimId);
    assertThat(after).isEqualTo(before);
  }

  @Test
  @DisplayName("C12 additional_information이 null이면 컬럼도 null로 저장되고 null로 복원된다")
  void additionalInformation_null_staysNull() {
    UUID claimId = persistClaim(null);

    byte[] stored = jdbcTemplate.queryForObject(
        "SELECT additional_information FROM user_claims WHERE id = ?", byte[].class, claimId);

    assertThat(stored).isNull();
    assertThat(userClaimRepository.findById(claimId).orElseThrow().getAdditionalInformation()).isNull();
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
      if (matchesAt(haystack, needle, offset)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesAt(byte[] haystack, byte[] needle, int offset) {
    for (int index = 0; index < needle.length; index++) {
      if (haystack[offset + index] != needle[index]) {
        return false;
      }
    }
    return true;
  }
}
