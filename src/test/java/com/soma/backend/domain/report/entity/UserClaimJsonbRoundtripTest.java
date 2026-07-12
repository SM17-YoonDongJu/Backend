package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.Hospitalization;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.report.repository.UserClaimRepository;

/**
 * USER_CLAIMS.details(jsonb) ↔ sealed {@link ClaimDetails} 왕복 검증(design.md §3.2).
 * SB4/Jackson3 + Hibernate7 환경에서 다형성 직렬화·역직렬화가 실제 PostgreSQL jsonb 컬럼을 통해
 * 서브타입까지 보존되는지 확인한다. 로컬/CI PostgreSQL 필요. @Transactional로 종료 시 롤백된다.
 */
@SpringBootTest
@Transactional
class UserClaimJsonbRoundtripTest {

  @Autowired
  private UserClaimRepository userClaimRepository;

  @PersistenceContext
  private EntityManager entityManager;

  @Test
  void medicalIndemnityDetails_roundtrips_through_jsonb() {
    UUID userId = UUID.randomUUID();
    // user_claims.user_id는 users FK(NOT NULL) — 픽스처로 users 행을 먼저 만든다.
    // Hibernate 세션 커넥션에 직접 INSERT해 아래 flush와 동일 트랜잭션·커넥션임을 보장한다.
    // TODO: 회원(user) 도메인의 User 엔티티가 개발되면 userRepository.save(user)로 교체.
    entityManager.unwrap(Session.class).doWork(connection -> {
      String insertUser = "INSERT INTO users (id, role, nickname, birth_date, gender) "
          + "VALUES (?, 'USER', '테스트유저', DATE '2000-01-01', '')";
      try (PreparedStatement ps = connection.prepareStatement(insertUser)) {
        ps.setObject(1, userId);
        ps.executeUpdate();
      }
    });

    ClaimDetails details = new MedicalIndemnityDetails(
        List.of("급성 충수염", "복막염"),
        List.of(new Hospitalization(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), "수술 입원")),
        List.of("입원", "수술"),
        List.of("2026-01-10"),
        "included",
        "2026-01-02",
        null);
    UserClaim saved = userClaimRepository.save(UserClaim.create(
        userId, null, 1_420_000L, LocalDate.of(2026, 1, 1),
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
