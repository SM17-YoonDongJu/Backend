package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * USER_CLAIMS.details(jsonb) ↔ sealed {@link ClaimDetails} 왕복 검증(design.md §3.2).
 * SB4/Jackson3 + Hibernate7 환경에서 다형성 직렬화·역직렬화가 실제 PostgreSQL jsonb 컬럼을 통해
 * 서브타입까지 보존되는지 확인한다. 로컬/CI PostgreSQL 필요. @Transactional로 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserClaimJsonbRoundtripTest {

  @Autowired
  private UserClaimRepository userClaimRepository;

  @Autowired
  private UserRepository userRepository;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
  void medicalIndemnityDetails_roundtrips_through_jsonb() {
    // user_claims.user_id는 users FK(NOT NULL) — 픽스처로 users 행을 먼저 만든다.
    // 네이티브 INSERT는 스키마의 NOT NULL(status·created_at 등)·default 유무에 취약하므로,
    // User.create로 저장해 JPA 매핑·Auditing이 값을 채우게 한다(test·default 프로파일 스키마 모두 정합).
    User user = userRepository.save(
        User.create("테스트유저", LocalDate.of(2000, 1, 1), "", null, Role.USER, null));
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
  }
}
