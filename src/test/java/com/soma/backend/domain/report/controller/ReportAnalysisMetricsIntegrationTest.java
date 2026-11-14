package com.soma.backend.domain.report.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportDraftMetricsViewFixture;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * GET /reports/{reportId}/analysis-metrics 통합 테스트(design.md §11 T5) — 실제 test_db의 {@code ai.report_drafts}
 * 미러에 초안을 커밋하고 컨트롤러부터 Spring 전역 {@code JsonMapper}·서브셀렉트 SQL까지 그대로 태운다.
 *
 * <p>{@code @Transactional}을 붙이지 않는다 — 리더가 REQUIRES_NEW라 테스트 트랜잭션에서 커밋되지 않은 행을 보지
 * 못한다. 픽스처는 커밋하고 {@code @AfterEach}에서 이 테스트가 만든 행만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("분석 근거 수치 API 통합 테스트")
class ReportAnalysisMetricsIntegrationTest {

  /** 초안 본문(sections)에 심는 마커. metrics만 읽으므로 응답 어디에도 나오면 안 된다. */
  private static final String BODY_MARKER = "BODY-MARKER-396";
  /** metrics 밖 초안 키(draft.disability)에 심는 마커. */
  private static final String DRAFT_DISABILITY_MARKER = "DRAFT-DISABILITY-MARKER-396";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private JsonMapper jsonMapper;

  private final List<UUID> createdReportIds = new ArrayList<>();

  /** 이 테스트가 만든 리포트·초안 행만 지운다. REQUIRES_NEW 가시성 때문에 커밋 경로로 검증해서 롤백이 없다. */
  @AfterEach
  void cleanUp() {
    for (UUID reportId : createdReportIds) {
      jdbcTemplate.update("DELETE FROM ai.report_drafts WHERE report_id = ?", reportId);
    }
    reportRepository.deleteAllById(createdReportIds);
    createdReportIds.clear();
  }

  private UUID saveReport(UUID ownerId, String confidenceLevel) {
    return saveReport(ownerId, confidenceLevel, ReportStatus.AWAITING_INSPECTION);
  }

  private UUID saveReport(UUID ownerId, String confidenceLevel, ReportStatus status) {
    Report report = Report.createPending(ownerId, null, null, AccidentType.DISABILITY, "후유장해 청구가 가능한가요?",
        "RAM-" + UUID.randomUUID().toString().substring(0, 12));
    ReflectionTestUtils.setField(report, "confidenceLevel", confidenceLevel);
    ReflectionTestUtils.setField(report, "status", status);
    UUID reportId = reportRepository.save(report).getId();
    createdReportIds.add(reportId);
    return reportId;
  }

  private void insertDraft(UUID reportId, String draftJson, String status) {
    jdbcTemplate.update("INSERT INTO ai.report_drafts (report_id, draft, status) VALUES (?, ?::jsonb, ?)",
        reportId, draftJson, status);
  }

  /** 골든 v1 metrics에 모르는 키(최상위·그룹·배열 원소)를 섞고, metrics 밖 본문·장해 키에 마커를 심은 초안. */
  private String goldenDraftWithUnknownKeysAndBody() {
    ObjectNode metrics = (ObjectNode) jsonMapper.readTree(ReportDraftMetricsViewFixture.GOLDEN_V1_METRICS);
    metrics.putObject("future_group").put("secret", "FUTURE-MARKER-396");
    ((ObjectNode) metrics.get("input")).put("new_field", "NEW-FIELD-MARKER-396");
    ((ObjectNode) metrics.get("pipeline").get("nodes").get(0)).put("extra", "EXTRA-MARKER-396");
    ObjectNode draft = jsonMapper.createObjectNode();
    draft.putObject("sections").put("6_본문", "보험금 청구 검토 본문 " + BODY_MARKER);
    draft.putObject("disability").put("note", DRAFT_DISABILITY_MARKER);
    draft.set("metrics", metrics);
    return jsonMapper.writeValueAsString(draft);
  }

  private static RequestPostProcessor as(UUID userId, String role) {
    CustomUserDetails principal = new CustomUserDetails(userId, role);
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private ResultActions getMetrics(UUID reportId, UUID userId, String role) throws Exception {
    return mockMvc.perform(get("/reports/{id}/analysis-metrics", reportId).with(as(userId, role)));
  }

  private static void expectUnavailable(ResultActions actions, String reason) throws Exception {
    actions.andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(false))
        .andExpect(jsonPath("$.data.unavailable_reason").value(reason))
        .andExpect(jsonPath("$.data.version").value(nullValue()))
        .andExpect(jsonPath("$.data.confidence").value(nullValue()))
        .andExpect(jsonPath("$.data.input").value(nullValue()))
        .andExpect(jsonPath("$.data.retrieval").value(nullValue()))
        .andExpect(jsonPath("$.data.verification").value(nullValue()))
        .andExpect(jsonPath("$.data.pipeline").value(nullValue()));
  }

  @Test
  @DisplayName("① 골든 v1 — 전 필드가 snake_case로 나가고 등급은 컬럼 정규화값, 모르는 키·본문은 응답에 없다")
  void availableGoldenMetrics() throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, "medium");
    insertDraft(reportId, goldenDraftWithUnknownKeysAndBody(), "draft");

    getMetrics(reportId, ownerId, "USER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.available").value(true))
        .andExpect(jsonPath("$.data.unavailable_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.version").value(1))
        .andExpect(jsonPath("$.data.confidence.level").value("MEDIUM"))
        .andExpect(jsonPath("$.data.confidence.reasons").value(contains(
            "가입금액 미보유 — 제시액 기준 어림 범위", "근거 미확인 주장 1건", "인용 검증 범위 일부(96%)",
            "근거에 없는 조항 인용 1건", "후유장해 지급률 추정 불확실")))
        .andExpect(jsonPath("$.data.input.document_count").value(3))
        .andExpect(jsonPath("$.data.input.doc_types").value(contains("policy", "diagnosis", "medical_receipt")))
        .andExpect(jsonPath("$.data.input.ocr_confidence.min").value(0.93))
        .andExpect(jsonPath("$.data.input.ocr_confidence.avg").value(0.94))
        .andExpect(jsonPath("$.data.input.doc_type_confidence.min").value(0.8))
        .andExpect(jsonPath("$.data.input.doc_type_confidence.avg").value(0.9))
        .andExpect(jsonPath("$.data.input.pii_masked.total").value(6))
        .andExpect(jsonPath("$.data.input.pii_masked.by_label['이름']").value(3))
        .andExpect(jsonPath("$.data.input.pii_masked.by_label['전화번호']").value(2))
        .andExpect(jsonPath("$.data.input.pii_masked.by_label['주민등록번호']").value(1))
        .andExpect(jsonPath("$.data.input.pii_masked.documents").value(4))
        .andExpect(jsonPath("$.data.input.pii_masked.free_text").value(2))
        .andExpect(jsonPath("$.data.retrieval.policy_matched").value(true))
        .andExpect(jsonPath("$.data.retrieval.evidence.terms").value(2))
        .andExpect(jsonPath("$.data.retrieval.evidence.schedule").value(1))
        .andExpect(jsonPath("$.data.retrieval.evidence.case").value(2))
        .andExpect(jsonPath("$.data.retrieval.evidence.case_law").doesNotExist())
        .andExpect(jsonPath("$.data.retrieval.case_funnel.candidates").value(12))
        .andExpect(jsonPath("$.data.retrieval.case_funnel.deduped").value(9))
        .andExpect(jsonPath("$.data.retrieval.case_funnel.relevant").value(2))
        .andExpect(jsonPath("$.data.retrieval.case_funnel.kept").value(2))
        .andExpect(jsonPath("$.data.retrieval.disability_schedule_source").value("terms"))
        .andExpect(jsonPath("$.data.verification.disability.extracted").value(2))
        .andExpect(jsonPath("$.data.verification.disability.verified").value(2))
        .andExpect(jsonPath("$.data.verification.disability.combined_rate").value(20.0))
        .andExpect(jsonPath("$.data.verification.disability.confidence").value("low"))
        .andExpect(jsonPath("$.data.verification.disability.pending_reeval").value(false))
        .andExpect(jsonPath("$.data.verification.disability.reclassified[0].injury").value("추간판 탈출"))
        .andExpect(jsonPath("$.data.verification.disability.reclassified[0].from").value(30.0))
        .andExpect(jsonPath("$.data.verification.disability.reclassified[0].to").value(20.0))
        .andExpect(jsonPath("$.data.verification.clause_citations.cited").value(3))
        .andExpect(jsonPath("$.data.verification.clause_citations.grounded").value(2))
        .andExpect(jsonPath("$.data.verification.clause_citations.ungrounded").value(contains("제99조")))
        .andExpect(jsonPath("$.data.verification.clause_citations.statute_mentions").value(1))
        .andExpect(jsonPath("$.data.verification.judge.ran").value(true))
        .andExpect(jsonPath("$.data.verification.judge.unavailable").value(false))
        .andExpect(jsonPath("$.data.verification.judge.flagged").value(1))
        .andExpect(jsonPath("$.data.verification.judge.checked_chars").value(12000))
        .andExpect(jsonPath("$.data.verification.judge.total_chars").value(12480))
        .andExpect(jsonPath("$.data.verification.judge.coverage").value(0.96))
        .andExpect(jsonPath("$.data.verification.judge.evidence_chunks").value(5))
        .andExpect(jsonPath("$.data.pipeline.node_count").value(12))
        .andExpect(jsonPath("$.data.pipeline.failed_nodes").isEmpty())
        .andExpect(jsonPath("$.data.pipeline.total_ms").value(73043))
        .andExpect(jsonPath("$.data.pipeline.nodes[*].node").value(contains("load_context", "case_search")))
        .andExpect(jsonPath("$.data.pipeline.nodes[0].ms").value(140))
        .andExpect(jsonPath("$.data.pipeline.nodes[0].status").value("ok"))
        // 허용 목록 — 모르는 키와 metrics 밖 초안(본문·장해)은 어떤 형태로도 응답에 나가지 않는다.
        .andExpect(content().string(not(containsString(BODY_MARKER))))
        .andExpect(content().string(not(containsString(DRAFT_DISABILITY_MARKER))))
        .andExpect(content().string(not(containsString("FUTURE-MARKER-396"))))
        .andExpect(content().string(not(containsString("future_group"))))
        .andExpect(content().string(not(containsString("NEW-FIELD-MARKER-396"))))
        .andExpect(content().string(not(containsString("EXTRA-MARKER-396"))))
        .andExpect(content().string(not(containsString("sections"))));
  }

  @Test
  @DisplayName("② 초안 행이 없으면 200 + NO_DRAFT이고 나머지는 null이다")
  void noDraftRow() throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, null);

    expectUnavailable(getMetrics(reportId, ownerId, "USER"), "NO_DRAFT");
  }

  @ParameterizedTest
  @EnumSource(value = ReportStatus.class, names = {"BLOCKED", "NEEDS_REUPLOAD"})
  @DisplayName("②-1 BLOCKED·NEEDS_REUPLOAD는 이전 실행의 초안이 남아 있어도 그 수치를 내지 않고 200 + NO_DRAFT다")
  void skippedReportWithStaleDraftIsNoDraft(ReportStatus status) throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, "medium", status);
    insertDraft(reportId, goldenDraftWithUnknownKeysAndBody(), "draft");

    expectUnavailable(getMetrics(reportId, ownerId, "USER"), "NO_DRAFT");
  }

  @Test
  @DisplayName("③ 초안에 metrics 키가 없으면(AI#92 이전 리포트·수치 계산 실패) 200 + LEGACY_REPORT다")
  void draftWithoutMetricsKey() throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, "high");
    insertDraft(reportId, "{\"sections\": {\"6_본문\": \"" + BODY_MARKER + "\"}}", "draft");

    expectUnavailable(getMetrics(reportId, ownerId, "USER"), "LEGACY_REPORT");
  }

  @Test
  @DisplayName("④ metrics가 JSON null이면 200 + LEGACY_REPORT다")
  void draftWithNullMetrics() throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, "high");
    insertDraft(reportId, "{\"metrics\": null, \"sections\": {}}", "draft");

    expectUnavailable(getMetrics(reportId, ownerId, "USER"), "LEGACY_REPORT");
  }

  @Test
  @DisplayName("④-1 draft가 객체가 아니면(->가 SQL NULL) 200 + LEGACY_REPORT다")
  void nonObjectDraftIsLegacy() throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, "high");
    insertDraft(reportId, "[1, 2]", "draft");

    expectUnavailable(getMetrics(reportId, ownerId, "USER"), "LEGACY_REPORT");
  }

  @Test
  @DisplayName("⑤ 사정사(CERTIFICATED·UNCERTIFICATED)는 남의 리포트도 200이다(초안 status가 signed여도 그대로)")
  void adjustersCanReadOthersMetrics() throws Exception {
    UUID reportId = saveReport(UUID.randomUUID(), "medium");
    insertDraft(reportId, goldenDraftWithUnknownKeysAndBody(), "signed");

    getMetrics(reportId, UUID.randomUUID(), "CERTIFICATED_ADJUSTER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(true))
        .andExpect(jsonPath("$.data.retrieval.evidence.case").value(2));
    getMetrics(reportId, UUID.randomUUID(), "UNCERTIFICATED_ADJUSTER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(true));
  }

  @Test
  @DisplayName("⑤ 다른 USER와 소유자가 아닌 ADMIN은 403 FORBIDDEN이다")
  void othersAreForbidden() throws Exception {
    UUID reportId = saveReport(UUID.randomUUID(), "medium");
    insertDraft(reportId, goldenDraftWithUnknownKeysAndBody(), "draft");

    getMetrics(reportId, UUID.randomUUID(), "USER")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    getMetrics(reportId, UUID.randomUUID(), "ADMIN")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("⑤ 없는 리포트는 사정사로 불러도 404 REPORT_NOT_FOUND다(존재 확인이 인가보다 먼저)")
  void missingReportIsNotFoundEvenForAdjuster() throws Exception {
    getMetrics(UUID.randomUUID(), UUID.randomUUID(), "CERTIFICATED_ADJUSTER")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
  }

  @Test
  @DisplayName("⑥ confidence_level 컬럼이 비어 있으면 level은 null이고 사유는 저장된 값이다")
  void nullColumnLevelKeepsReasons() throws Exception {
    UUID ownerId = UUID.randomUUID();
    UUID reportId = saveReport(ownerId, null);
    insertDraft(reportId, goldenDraftWithUnknownKeysAndBody(), "draft");

    getMetrics(reportId, ownerId, "USER")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(true))
        .andExpect(jsonPath("$.data.confidence.level").value(nullValue()))
        .andExpect(jsonPath("$.data.confidence.reasons.length()").value(5));
  }
}
