package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.dto.ReviewWorkspaceResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReviewContextRow;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 검수 워크스페이스 조회 회귀 테스트(design.md §12.4 C28, §7.3 변경 경로).
 *
 * <p>{@code user_claims.additional_information}이 {@code bytea}가 되면서 native 프로젝션
 * ({@code ReportRepository.findReviewContext})에서 빠지고 {@code UserClaim} 엔티티 경유로 옮겨졌다.
 * native 결과에는 컨버터가 적용되지 않으므로, 셀렉트가 남아 있으면 봉투 바이트가 문자열로 새어 나온다 —
 * 이 테스트는 (1) native 프로젝션이 실제 test_db에서 실행되고, (2) API 응답의
 * {@code claim.additional_information}이 복호화된 평문으로 내려오는지를 함께 고정한다.
 *
 * <p>{@code insurance_products}·{@code insurers}는 아직 엔티티로 매핑되지 않아 {@code ddl-auto: create-drop}
 * 스키마에 생성되지 않는다. native 쿼리가 참조하므로 테스트에서 최소 형태로 만들어 준다(운영은 Flyway 관리).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("검수 워크스페이스 PII 회귀 테스트")
class ReviewWorkspacePiiRegressionTest {

  private static final String ADDITIONAL_INFORMATION = "보험사 안내와 달라 추가 설명합니다(연락처 010-1234-5678)";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ReviewWorkspaceQueryService reviewWorkspaceQueryService;

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

  private UUID reportId;
  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS insurers (id uuid PRIMARY KEY, name varchar(255))");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS insurance_products ("
            + "id uuid PRIMARY KEY, insurer_id uuid, product_name varchar(255))");
    UUID insurerId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO insurers (id, name) VALUES (?, ?)", insurerId, "OO손해보험");
    jdbcTemplate.update(
        "INSERT INTO insurance_products (id, insurer_id, product_name) VALUES (?, ?, ?)",
        productId, insurerId, "무배당 행복드림 종합보험");

    User owner = userRepository.save(
        User.create("의뢰인", LocalDate.of(1993, 7, 7), "female", null, Role.USER, List.of("서울")));
    User adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 2, 2), "male", null, Role.CERTIFICATED_ADJUSTER, null));
    adjusterId = adjuster.getId();

    ClaimDetails details = new MedicalIndemnityDetails(
        List.of("급성 충수염"), List.of(), List.of("입원"), List.of("2026-01-10"), "included", "2026-01-02", null);
    UserClaim claim = userClaimRepository.save(UserClaim.create(
        owner.getId(), productId, 1_000_000, LocalDate.of(2026, 1, 1),
        AccidentType.MEDICAL_INDEMNITY, details, "질문", "사고 경위입니다", ADDITIONAL_INFORMATION));
    Report report = reportRepository.save(Report.createPending(
        owner.getId(), productId, claim.getId(), AccidentType.MEDICAL_INDEMNITY, "질문",
        "PII-" + UUID.randomUUID().toString().substring(0, 8)));
    reportId = report.getId();

    entityManager.flush();
    entityManager.clear();
  }

  private RequestPostProcessor authenticatedAsAdjuster() {
    CustomUserDetails principal = new CustomUserDetails(adjusterId, "CERTIFICATED_ADJUSTER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("C28 GET /reports/{id}/review 응답의 additional_information이 복호화된 평문으로 내려온다")
  void reviewWorkspace_returnsDecryptedAdditionalInformation() throws Exception {
    mockMvc.perform(get("/reports/{reportId}/review", reportId).with(authenticatedAsAdjuster()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.claim.additional_information").value(ADDITIONAL_INFORMATION))
        .andExpect(jsonPath("$.data.claim.description").value("사고 경위입니다"))
        .andExpect(jsonPath("$.data.claim.product_name").value("무배당 행복드림 종합보험"))
        .andExpect(jsonPath("$.data.claim.insurer_name").value("OO손해보험"));
  }

  @Test
  @DisplayName("C28 서비스 계층에서도 additional_information이 봉투 바이트가 아니라 평문으로 조립된다")
  void reviewWorkspaceService_assemblesPlaintext() {
    ReviewWorkspaceResponse response = reviewWorkspaceQueryService.getReviewWorkspace(reportId, adjusterId);

    assertThat(response.claim()).isNotNull();
    assertThat(response.claim().additionalInformation()).isEqualTo(ADDITIONAL_INFORMATION);
    assertThat(response.region()).isEqualTo("서울");
  }

  @Test
  @DisplayName("C28 native 프로젝션(findReviewContext)은 additional_information을 더 이상 셀렉트하지 않는다")
  void findReviewContext_doesNotExposeAdditionalInformation() {
    ReviewContextRow context = reportRepository.findReviewContext(reportId);

    assertThat(context).isNotNull();
    assertThat(context.getClaimDescription()).isEqualTo("사고 경위입니다");
    assertThat(context.getProductName()).isEqualTo("무배당 행복드림 종합보험");
    // 프로젝션 인터페이스에 getAdditionalInformation()이 남아 있으면 컴파일되지 않아야 한다.
    assertThat(ReviewContextRow.class.getMethods())
        .noneMatch(method -> method.getName().equals("getAdditionalInformation"));
  }
}
