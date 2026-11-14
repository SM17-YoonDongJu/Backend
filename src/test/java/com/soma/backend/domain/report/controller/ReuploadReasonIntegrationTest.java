package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.micrometer.core.instrument.MeterRegistry;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ClaimReadinessView;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.service.ClaimReadinessReader;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * #401 재업로드 사유 통합 테스트(design.md §9-2 I1) — {@code ai.claim_readiness}의 실제 행이 목록·상세·폴링 JSON의
 * {@code analysis_failure_reason}·{@code analysis_failure_message}(폴링은 {@code failure_*})로 이어지는지 test_db에서
 * 확인한다. {@code @Subselect} + {@code text[]} 매핑 선례가 없어 1원소·2원소·빈 배열을 실제로 읽어 본다(§4-3).
 *
 * <p><b>{@code @Transactional}을 쓰지 않는다</b> — 리더가 REQUIRES_NEW(별도 커넥션)라 미커밋 행을 못 본다. 픽스처를
 * 커밋하고 {@code @AfterEach}에서 이 테스트가 만든 행만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("재업로드 사유(ai.claim_readiness) 통합 테스트")
class ReuploadReasonIntegrationTest {

  private static final String GENERIC_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";
  private static final String DOMAIN_MESSAGE =
      "업로드하신 문서에서 보험 청구 서류를 찾지 못했어요. 보험증권·진단서 같은 청구 서류를 올려주세요.";
  private static final String POLICY_MESSAGE = "업로드하신 문서에서 보험증권을 확인하지 못했어요. 보험증권을 다시 올려주세요.";
  private static final String BOTH_MESSAGE =
      "업로드하신 문서에서 보험증권과 진단서를 확인하지 못했어요. 두 서류를 다시 올려주세요.";
  private static final String MISSING_GENERIC_MESSAGE = "보험증권과 진단서가 모두 필요해요. 두 서류를 함께 다시 올려주세요.";
  private static final String REUPLOAD_REASON_CHECK = "claim_readiness_reupload_reason_check";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private MeterRegistry meterRegistry;
  @Autowired
  private ClaimReadinessReader claimReadinessReader;

  private UUID ownerId;
  private final List<UUID> createdReportIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
  }

  @AfterEach
  void cleanUp() {
    // 커밋 경로로 검증하므로 이 테스트가 만든 리포트와 그 리포트의 판정 행만 직접 지운다.
    for (UUID reportId : createdReportIds) {
      jdbcTemplate.update("DELETE FROM ai.claim_readiness WHERE report_id = ?", reportId);
      jdbcTemplate.update("DELETE FROM ai.ocr_results WHERE report_id = ?", reportId);
    }
    reportRepository.deleteAllById(createdReportIds);
    createdReportIds.clear();
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  /** AI 워커가 원시 SQL로 세팅하는 종료 상태를 같은 값으로 만든다(전이표를 거치지 않는 진입). */
  private UUID saveReport(String status) {
    UUID reportId = reportRepository.save(Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "RRI-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    createdReportIds.add(reportId);
    jdbcTemplate.update("UPDATE reports SET status = ? WHERE id = ?", status, reportId);
    return reportId;
  }

  /** 청구 판정 행 — 배열은 {@code '{policy}'} 같은 PostgreSQL 배열 리터럴 문자열로 넣는다. */
  private void insertReadiness(UUID reportId, String missingDocTypes, String status, String reuploadReason) {
    jdbcTemplate.update("""
        INSERT INTO ai.claim_readiness (claim_id, report_id, doc_total, missing_doc_types, status, reupload_reason)
        VALUES (?, ?, 2, ?::text[], ?, ?)
        """, UUID.randomUUID().toString(), reportId, missingDocTypes, status, reuploadReason);
  }

  private double reuploadDegraded(String cause) {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", cause).counter().count();
  }

  private static Matcher<Object> valueOrNull(String expected) {
    return expected == null ? nullValue() : is((Object) expected);
  }

  /** 목록·상세·폴링 세 응답이 같은 사유 코드·문구를 싣는지 확인한다(폴링은 재업로드 안내·실패 시각까지). */
  private void assertAllEndpoints(UUID reportId, String reason, String message) throws Exception {
    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason", valueOrNull(reason)))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_message").value(message));
    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.analysis_failure_reason", valueOrNull(reason)))
        .andExpect(jsonPath("$.data.analysis_failure_message").value(message));
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.failure_reason", valueOrNull(reason)))
        .andExpect(jsonPath("$.data.failure_message").value(message))
        .andExpect(jsonPath("$.data.reupload_guidance").value("RECOMMENDED"))
        .andExpect(jsonPath("$.data.failed_at").value(nullValue()))
        .andExpect(jsonPath("$.data.failed_documents").isEmpty());
  }

  static Stream<Arguments> recordedReasons() {
    return Stream.of(
        Arguments.of("off_domain", "{}", "DOMAIN_MISMATCH", DOMAIN_MESSAGE),
        Arguments.of("missing_required", "{policy}", "MISSING_REQUIRED_DOCUMENTS", POLICY_MESSAGE),
        Arguments.of("missing_required", "{policy,diagnosis}", "MISSING_REQUIRED_DOCUMENTS", BOTH_MESSAGE),
        Arguments.of("missing_required", "{}", "MISSING_REQUIRED_DOCUMENTS", MISSING_GENERIC_MESSAGE));
  }

  @ParameterizedTest(name = "{0} {1} → {2}")
  @MethodSource("recordedReasons")
  @DisplayName("사유가 기록된 NEEDS_REUPLOAD는 목록·상세·폴링 모두 사유 코드와 사유별 문구를 싣는다(text[] 매핑 포함)")
  void recordedReasonFlowsToEveryEndpoint(String reupload, String missingDocTypes, String reason, String message)
      throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");
    insertReadiness(reportId, missingDocTypes, "blocked", reupload);

    assertAllEndpoints(reportId, reason, message);
  }

  @Test
  @DisplayName("@Subselect + text[] — missing_doc_types가 원소 순서 그대로 List로 읽히고 빈 배열은 null이 아닌 빈 리스트다")
  void subselectReadsTextArrayAsList() {
    // Given: 선례(물리 테이블 엔티티)와 달리 @Subselect 뷰에서 배열을 읽는 첫 매핑이라 엔티티 값 자체를 확인한다
    UUID single = saveReport("NEEDS_REUPLOAD");
    UUID both = saveReport("NEEDS_REUPLOAD");
    UUID empty = saveReport("NEEDS_REUPLOAD");
    insertReadiness(single, "{policy}", "blocked", "missing_required");
    insertReadiness(both, "{diagnosis,policy}", "blocked", "missing_required");
    insertReadiness(empty, "{}", "published", null);

    // When: 운영 경로와 같은 REQUIRES_NEW 리더로 읽는다
    Map<UUID, List<ClaimReadinessView>> rows = claimReadinessReader.findByReportIds(List.of(single, both, empty));

    // Then
    assertThat(rows.get(single)).singleElement()
        .satisfies(row -> assertThat(row.getMissingDocTypes()).containsExactly("policy"));
    assertThat(rows.get(both)).singleElement()
        .satisfies(row -> assertThat(row.getMissingDocTypes()).containsExactly("diagnosis", "policy"));
    assertThat(rows.get(empty)).singleElement().satisfies(row -> {
      assertThat(row.getMissingDocTypes()).isNotNull().isEmpty();
      assertThat(row.getReuploadReason()).isNull();
      assertThat(row.reuploadCause()).isNull();
    });
  }

  @Test
  @DisplayName("unreadable은 지금과 같은 응답이다(사유 null·일반 문구)")
  void unreadableKeepsCurrentResponse() throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");
    insertReadiness(reportId, "{}", "published", "unreadable");

    assertAllEndpoints(reportId, null, GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("reupload_reason이 NULL인 행(013 이전 판정·재업로드 아님)은 지금과 같은 응답이다")
  void nullReasonKeepsCurrentResponse() throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");
    insertReadiness(reportId, "{}", "published", null);

    assertAllEndpoints(reportId, null, GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("행이 없으면(단독 문서 리포트) 지금과 같은 응답이다")
  void missingRowKeepsCurrentResponse() throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");

    assertAllEndpoints(reportId, null, GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("같은 report_id 행이 2개면(계약 위반) 지금과 같은 응답이고 요청마다 ambiguous_rows가 오른다")
  void ambiguousRowsKeepCurrentResponse() throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");
    insertReadiness(reportId, "{}", "blocked", "off_domain");
    insertReadiness(reportId, "{policy}", "blocked", "missing_required");
    double before = reuploadDegraded("ambiguous_rows");

    assertAllEndpoints(reportId, null, GENERIC_MESSAGE);

    assertThat(reuploadDegraded("ambiguous_rows") - before).isEqualTo(3.0);
  }

  @Test
  @DisplayName("사유와 품질 미달 문서가 함께 있으면 사유 문구와 문서 목록이 같이 실린다(폴링)")
  void reasonAndQualityGateDocumentsCoexist() throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");
    insertReadiness(reportId, "{}", "blocked", "off_domain");
    UUID attachmentId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_results (id, report_id, claim_id, attachment_id, doc_type, doc_index, ocr_quality)
        VALUES (?, ?, 'claim-401', ?, 'diagnosis', 0, 'needs_reupload')
        """, UUID.randomUUID(), reportId, attachmentId);

    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failure_reason").value("DOMAIN_MISMATCH"))
        .andExpect(jsonPath("$.data.failure_message").value(DOMAIN_MESSAGE))
        .andExpect(jsonPath("$.data.failed_documents[0].attachment_id").value(attachmentId.toString()))
        .andExpect(jsonPath("$.data.failed_documents[0].failure_reason").value(nullValue()));
  }

  @Test
  @DisplayName("NEEDS_REUPLOAD가 아닌 리포트는 off_domain 행이 있어도 사유를 싣지 않는다(BLOCKED 우선순위 유지)")
  void otherStatusesIgnoreRecordedReason() throws Exception {
    UUID reportId = saveReport("BLOCKED");
    insertReadiness(reportId, "{}", "blocked", "off_domain");

    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("BLOCKED"))
        .andExpect(jsonPath("$.data.failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.failure_message").value("처리할 수 없는 요청이에요. 고객센터로 문의해주세요."));
  }

  @Test
  @DisplayName("모르는 사유 값(AI가 CHECK를 먼저 넓힌 경우)도 200 + 지금과 같은 응답이다")
  void unknownReasonKeepsCurrentResponse() throws Exception {
    UUID reportId = saveReport("NEEDS_REUPLOAD");
    // CHECK가 막는 값을 넣으려고 이 테스트 안에서만 제약을 내렸다가 같은 정의로 되돌린다.
    // IF EXISTS — 앞선 실행이 복구 전에 끊겨 제약이 이미 없어도 이 클래스만 다시 돌릴 수 있게 한다.
    jdbcTemplate.execute("ALTER TABLE ai.claim_readiness DROP CONSTRAINT IF EXISTS " + REUPLOAD_REASON_CHECK);
    try {
      insertReadiness(reportId, "{}", "blocked", "brand_new");

      assertAllEndpoints(reportId, null, GENERIC_MESSAGE);
    } finally {
      jdbcTemplate.update("DELETE FROM ai.claim_readiness WHERE report_id = ?", reportId);
      jdbcTemplate.execute("ALTER TABLE ai.claim_readiness ADD CONSTRAINT " + REUPLOAD_REASON_CHECK
          + " CHECK (reupload_reason IN ('unreadable', 'off_domain', 'missing_required'))");
    }
  }
}
