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
import com.soma.backend.domain.report.entity.claim.Hospitalization;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.crypto.PiiEnvelope;

/**
 * USER_CLAIMS.details PII 암호화 통합 검증(이슈 #235, design.md §3.2).
 *
 * <p>details는 다형성 값 객체({@link ClaimDetails})를 JSON 문자열로 직렬화한 뒤 통째로 암호화해
 * {@code bytea}에 담는다({@code user_insurances.coverages}와 동일 패턴). 엔티티 왕복(다형성 서브타입까지
 * 보존)뿐 아니라, raw 컬럼을 직접 읽어 실제로 봉투(bytea)로 저장되는지도 확인한다.
 * 로컬/CI PostgreSQL 필요. @Transactional로 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserClaimDetailsEncryptionTest {

  @Autowired
  private UserClaimRepository userClaimRepository;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("다형성 서브타입까지 보존한 채 암호화 왕복한다")
  void medicalIndemnityDetails_roundtrips_throughEncryption() {
    // user_claims.user_id는 users FK(NOT NULL) — 픽스처로 users 행을 먼저 만든다.
    // 네이티브 INSERT는 스키마의 NOT NULL(status·created_at 등)·default 유무에 취약하므로,
    // User.create로 저장해 JPA 매핑·Auditing이 값을 채우게 한다(test·default 프로파일 스키마 모두 정합).
    User user = userRepository.save(
        User.create("테스트유저", LocalDate.of(2000, 1, 1), "", null, null, Role.USER, null));
    UUID userId = user.getId();

    ClaimDetails details = new MedicalIndemnityDetails(
        List.of("급성 충수염", "복막염"),
        List.of(new Hospitalization(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), "수술 입원")),
        List.of("입원", "수술"),
        List.of("2026-01-10"),
        "included",
        "2026-01-02",
        null);
    UserClaim saved = userClaimRepository.save(UserClaim.create(
        userId, null, 1_420_000, LocalDate.of(2026, 1, 1),
        AccidentType.MEDICAL_INDEMNITY, details, "보험금이 적게 나온 것 같아요", "사고 경위", null));

    entityManager.flush();
    entityManager.clear();

    UserClaim found = userClaimRepository.findById(saved.getId()).orElseThrow();
    assertThat(found.getDetails()).isInstanceOf(MedicalIndemnityDetails.class);
    MedicalIndemnityDetails reloaded = (MedicalIndemnityDetails) found.getDetails();
    assertThat(reloaded.type()).isEqualTo(AccidentType.MEDICAL_INDEMNITY);
    assertThat(reloaded.diagnosis()).containsExactly("급성 충수염", "복막염");
    assertThat(reloaded.hospitalizations()).hasSize(1);
    assertThat(reloaded.hospitalizations().get(0).hospitalReason()).isEqualTo("수술 입원");
    assertThat(reloaded.treatmentTypes()).containsExactly("입원", "수술");
    assertThat(reloaded.nonPaymentStatus()).isEqualTo("included");

    byte[] stored = jdbcTemplate.queryForObject(
        "SELECT details FROM user_claims WHERE id = ?", byte[].class, saved.getId());
    assertThat(stored).isNotNull();
    assertThat(stored.length).isGreaterThanOrEqualTo(PiiEnvelope.MIN_LENGTH);
    assertThat(stored[0]).isEqualTo(PiiEnvelope.VERSION_1);
    String asText = new String(stored, StandardCharsets.UTF_8);
    assertThat(asText).doesNotContain("급성 충수염").doesNotContain("복막염").doesNotContain("medical_indemnity");
  }
}
