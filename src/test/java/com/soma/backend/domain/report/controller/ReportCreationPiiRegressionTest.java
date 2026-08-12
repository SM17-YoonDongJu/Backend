package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.crypto.PiiEnvelope;

/**
 * POST /reports 생성 플로우 PII 회귀 테스트(design.md §12.4 C27).
 *
 * <p>{@code ReportCommandService.createReport}가 저장하는 {@code user_claims.additional_information}이
 * 컨버터를 지나 실제로 봉투(bytea)로 적재되는지, 그러면서도 202 응답 계약과 엔티티 재조회 값은 그대로인지를
 * 한 번에 고정한다. 컨버터가 배선되지 않아도 엔티티 왕복은 성공하므로, {@link JdbcTemplate}으로 raw 컬럼을
 * 직접 읽어 평문이 남지 않았음을 함께 확인한다.
 *
 * <p>실제 test_db를 쓰며 {@code @Transactional}로 종료 시 롤백한다. OCR 트리거는 아웃박스 테이블에만
 * 적재되므로(릴레이는 {@code app.outbox.enabled: false}) 외부 호출이 발생하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("리포트 생성 PII 회귀 테스트")
class ReportCreationPiiRegressionTest {

  private static final String ADDITIONAL_INFORMATION =
      "보험사 안내와 금액이 달라 부연합니다. 연락처는 010-9876-5432 입니다.";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private UserClaimRepository userClaimRepository;

  @Autowired
  private ReportRepository reportRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  private UUID userId;

  @BeforeEach
  void setUp() {
    userId = userRepository.save(
        User.create("리포트생성유저", LocalDate.of(1992, 4, 4), "female", null, Role.USER, List.of("서울")))
        .getId();
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private String requestBody(String additionalInformation) {
    String additionalInformationJson =
        additionalInformation == null ? "null" : "\"" + additionalInformation + "\"";
    return """
        {
          "accident_type": "medical_indemnity",
          "accident_date": "2026-01-01",
          "diagnosis": ["급성 충수염"],
          "offered_amount": 1000000,
          "hospitalizations": [],
          "description": "사고 경위입니다",
          "additional_information": %s,
          "question": "보장 여부가 궁금합니다",
          "documents": []
        }
        """.formatted(additionalInformationJson);
  }

  private UUID createReport(String additionalInformation) throws Exception {
    MvcResult result = mockMvc.perform(post("/reports")
            .with(authenticatedAsOwner())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody(additionalInformation)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("202"))
        .andExpect(jsonPath("$.data.report_id").isNotEmpty())
        .andExpect(jsonPath("$.data.status").value("AWAITING_INSPECTION"))
        .andReturn();
    entityManager.flush();
    entityManager.clear();
    return reportIdOf(result);
  }

  private UUID reportIdOf(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    int start = body.indexOf("\"report_id\":\"") + "\"report_id\":\"".length();
    return UUID.fromString(body.substring(start, start + 36));
  }

  private UUID claimIdOf(UUID reportId) {
    return reportRepository.findById(reportId).orElseThrow().getClaimId();
  }

  @Test
  @DisplayName("C27 202로 응답하고 additional_information은 봉투(bytea)로 저장된다")
  void createReport_storesAdditionalInformationEncrypted() throws Exception {
    UUID reportId = createReport(ADDITIONAL_INFORMATION);

    byte[] stored = jdbcTemplate.queryForObject(
        "SELECT additional_information FROM user_claims WHERE id = ?", byte[].class, claimIdOf(reportId));

    assertThat(stored).isNotNull();
    assertThat(stored.length).isGreaterThanOrEqualTo(PiiEnvelope.MIN_LENGTH);
    assertThat(stored[0]).isEqualTo(PiiEnvelope.VERSION_1);
    assertThat(stored[1]).isEqualTo(PiiEnvelope.SCOPE_TABLE_COLUMN);
    assertThat(new String(stored, StandardCharsets.UTF_8))
        .doesNotContain("010-9876-5432")
        .doesNotContain("보험사");
  }

  @Test
  @DisplayName("C27 저장된 청구는 엔티티 재조회 시 평문으로 복원되고 description도 암호화(bytea)로 저장된다")
  void createReport_claimRoundTripsThroughConverter() throws Exception {
    UUID claimId = claimIdOf(createReport(ADDITIONAL_INFORMATION));

    UserClaim claim = userClaimRepository.findById(claimId).orElseThrow();

    assertThat(claim.getAdditionalInformation()).isEqualTo(ADDITIONAL_INFORMATION);
    assertThat(claim.getDescription()).isEqualTo("사고 경위입니다");
    byte[] storedDescription = jdbcTemplate.queryForObject(
        "SELECT description FROM user_claims WHERE id = ?", byte[].class, claimId);
    assertThat(storedDescription).isNotNull();
    assertThat(storedDescription[0]).isEqualTo(PiiEnvelope.VERSION_1);
    assertThat(new String(storedDescription, StandardCharsets.UTF_8)).doesNotContain("사고 경위입니다");
  }

  @Test
  @DisplayName("C27 additional_information 미입력이면 컬럼도 null로 남는다")
  void createReport_withoutAdditionalInformation_storesNull() throws Exception {
    UUID claimId = claimIdOf(createReport(null));

    assertThat(jdbcTemplate.queryForObject(
        "SELECT additional_information FROM user_claims WHERE id = ?", byte[].class, claimId)).isNull();
    assertThat(userClaimRepository.findById(claimId).orElseThrow().getAdditionalInformation()).isNull();
  }
}
