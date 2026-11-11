package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;

/**
 * 검수 화면 맥락 조회({@code findReviewContext}) 실행 검증. native에서 QueryDSL 엔티티 조인으로 옮기면서
 * users는 INNER, user_claims·insurance_products·insurers는 LEFT JOIN이라는 조인 형태를 실제 test_db에서 고정한다.
 * 보험사·상품은 앱이 쓰지 않는 기준정보라 JDBC로 직접 넣는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReviewContextQueryExecutionTest {

  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private UserClaimRepository userClaimRepository;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @PersistenceContext
  private EntityManager entityManager;

  @Test
  @DisplayName("의뢰인·청구·상품·보험사를 모두 조인해 맥락 필드를 채운다")
  void fillsAllContextFields() {
    UUID insurerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO insurers (id, name) VALUES (?, ?)", insurerId, "OO손해보험");
    jdbcTemplate.update("INSERT INTO insurance_products (id, insurer_id, product_name) VALUES (?, ?, ?)",
        productId, insurerId, "행복드림 종합보험");
    User owner = saveOwner();
    UserClaim claim = userClaimRepository.save(UserClaim.create(
        owner.getId(), productId, 1_000_000, LocalDate.of(2026, 1, 1), AccidentType.MEDICAL_INDEMNITY,
        new MedicalIndemnityDetails(List.of("급성 충수염"), List.of(), List.of("입원"), List.of("2026-01-10"),
            "included", "2026-01-02", null),
        "질문", "사고 경위", null));
    Report report = reportRepository.save(Report.createPending(
        owner.getId(), productId, claim.getId(), AccidentType.MEDICAL_INDEMNITY, "질문", "RC-1"));
    flushAndClear();

    ReviewContextRow context = reportRepository.findReviewContext(report.getId());

    assertThat(context.nickname()).isEqualTo("의뢰인");
    assertThat(context.gender()).isEqualTo("female");
    assertThat(context.birthDate()).isEqualTo(LocalDate.of(1993, 7, 7));
    assertThat(context.joinedAt()).isNotNull();
    assertThat(context.claimAccidentType()).isEqualTo(AccidentType.MEDICAL_INDEMNITY);
    assertThat(context.accidentDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(context.productName()).isEqualTo("행복드림 종합보험");
    assertThat(context.insurerName()).isEqualTo("OO손해보험");
  }

  @Test
  @DisplayName("청구·상품이 없는 리포트도 행이 나오고 의뢰인 외 필드만 null이다(LEFT JOIN)")
  void leftJoinsKeepRowWithoutClaimOrProduct() {
    User owner = saveOwner();
    Report report = reportRepository.save(Report.createPending(
        owner.getId(), null, null, AccidentType.OTHER, "질문", "RC-2"));
    flushAndClear();

    ReviewContextRow context = reportRepository.findReviewContext(report.getId());

    assertThat(context).isNotNull();
    assertThat(context.nickname()).isEqualTo("의뢰인");
    assertThat(context.claimAccidentType()).isNull();
    assertThat(context.accidentDate()).isNull();
    assertThat(context.productName()).isNull();
    assertThat(context.insurerName()).isNull();
  }

  @Test
  @DisplayName("없는 리포트면 null이다")
  void returnsNullForUnknownReport() {
    assertThat(reportRepository.findReviewContext(UUID.randomUUID())).isNull();
  }

  private User saveOwner() {
    return userRepository.save(
        User.create("의뢰인", LocalDate.of(1993, 7, 7), "female", null, null, Role.USER, List.of("서울")));
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
