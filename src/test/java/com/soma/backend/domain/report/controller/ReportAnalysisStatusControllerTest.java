package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * GET /reports/{reportId}/analysis-status HTTP 계약·인가 테스트(design.md §15 Q10·Q11·Q14 + §12 S2·S3).
 *
 * <p>세 가지를 한 번에 고정한다.
 * <ol>
 *   <li><b>응답 계약</b> — 키는 전부 snake_case이고 {@code failed_documents}는 실패가 아니어도 빈 배열이다.</li>
 *   <li><b>정보 노출 차단</b> — 실패 저널의 내부 식별자({@code s3_key}·{@code user_ref}·{@code error_type}·
 *       {@code message_id}·{@code job_id}·{@code attempts})가 응답 본문에 단 한 글자도 나가면 안 된다.</li>
 *   <li><b>인가</b> — 소유자 전용이라 사정사도 403이다(§12 S3 권고를 코드가 실제로 따르는지 검증).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("분석 상태 조회 API 테스트")
class ReportAnalysisStatusControllerTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 12, 14, 3, 11);
  private static final String SECRET_S3_KEY = "s3://brbs-bucket/private/진단서-원본.pdf";
  private static final String SECRET_USER_REF = "internal-user-ref-9f2c";
  private static final String SECRET_ERROR_TYPE = "MaskingResidualDetectedError";
  private static final String SECRET_MESSAGE_ID = "sqs-message-id-7788";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportAttachmentRepository reportAttachmentRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "ANS-" + UUID.randomUUID().toString().substring(0, 12))).getId();
  }

  private RequestPostProcessor authenticatedAs(UUID userId, String role) {
    CustomUserDetails principal = new CustomUserDetails(userId, role);
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private UUID saveAttachment(String name) {
    return reportAttachmentRepository.save(
        ReportAttachment.of(reportId, name, "https://example.test/doc", "application/pdf", "diagnosis")).getId();
  }

  private UUID insertTerminalFailure(UUID attachmentId, String failureClass) {
    UUID jobId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, job_id, message_id, s3_key, user_ref, content_type, doc_type_hint, claim_id,
           report_id, attachment_id, failure_class, error_type, attempts, terminal, first_failed_at, last_failed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)
        """,
        UUID.randomUUID(), jobId, SECRET_MESSAGE_ID, SECRET_S3_KEY, SECRET_USER_REF,
        "application/pdf", "diagnosis", "claim-42",
        reportId, attachmentId, failureClass, SECRET_ERROR_TYPE, 5, FAILED_AT, FAILED_AT);
    return jobId;
  }

  @Test
  @DisplayName("Q14 — FAILED 응답의 키는 전부 snake_case이고 문서 배열까지 계약대로 내려온다")
  void failedResponseUsesSnakeCaseContract() throws Exception {
    // Given
    UUID attachmentId = saveAttachment("진단서.pdf");
    insertTerminalFailure(attachmentId, "unreadable_file");

    // When & Then
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAs(ownerId, "USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.analysis_state").value("FAILED"))
        .andExpect(jsonPath("$.data.failure_reason").value("UNREADABLE_FILE"))
        .andExpect(jsonPath("$.data.failure_message").value("파일을 읽을 수 없어요. 다시 업로드해주세요"))
        .andExpect(jsonPath("$.data.reupload_guidance").value("RECOMMENDED"))
        .andExpect(jsonPath("$.data.failed_at").exists())
        .andExpect(jsonPath("$.data.failed_documents", hasSize(1)))
        .andExpect(jsonPath("$.data.failed_documents[0].attachment_id").value(attachmentId.toString()))
        .andExpect(jsonPath("$.data.failed_documents[0].name").value("진단서.pdf"))
        .andExpect(jsonPath("$.data.failed_documents[0].failure_reason").value("UNREADABLE_FILE"))
        // camelCase 키가 함께 나가면 계약이 이원화된다.
        .andExpect(jsonPath("$.data.analysisState").doesNotExist())
        .andExpect(jsonPath("$.data.failureReason").doesNotExist())
        .andExpect(jsonPath("$.data.failedDocuments").doesNotExist());
  }

  @Test
  @DisplayName("Q14 — PROCESSING이면 실패 필드는 null이고 failed_documents는 빈 배열이다(null 아님)")
  void processingResponseHasEmptyDocumentArray() throws Exception {
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAs(ownerId, "USER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("PROCESSING"))
        .andExpect(jsonPath("$.data.failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.failure_message").value(nullValue()))
        .andExpect(jsonPath("$.data.reupload_guidance").value(nullValue()))
        .andExpect(jsonPath("$.data.failed_at").value(nullValue()))
        .andExpect(jsonPath("$.data.failed_documents").isArray())
        .andExpect(jsonPath("$.data.failed_documents", hasSize(0)));
  }

  @Test
  @DisplayName("S2 — 응답 본문에 s3_key·user_ref·error_type·message_id·job_id·attempts가 새지 않는다")
  void responseLeaksNoInternalIdentifiers() throws Exception {
    // Given: 저널 행에 내부 식별자를 모두 채워 둔다
    UUID attachmentId = saveAttachment("진단서.pdf");
    UUID jobId = insertTerminalFailure(attachmentId, "masking_residual");

    // When
    MvcResult result = mockMvc.perform(
            get("/reports/{id}/analysis-status", reportId).with(authenticatedAs(ownerId, "USER")))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

    // Then
    assertThat(body)
        .doesNotContain(SECRET_S3_KEY)
        .doesNotContain(SECRET_USER_REF)
        .doesNotContain(SECRET_ERROR_TYPE)
        .doesNotContain(SECRET_MESSAGE_ID)
        .doesNotContain(jobId.toString())
        .doesNotContain("s3_key", "user_ref", "error_type", "message_id", "job_id", "attempts", "claim_id");
    // 노출해도 되는 값(대표 사유·문구)은 정상적으로 들어 있다.
    assertThat(body).contains("MASKING_RESIDUAL", "문서를 검토 중입니다");
  }

  @Test
  @DisplayName("S3 회귀 — 사정사(CERTIFICATED_ADJUSTER)가 남의 리포트 분석 상태를 조회하면 403이다(소유자 전용)")
  void adjusterCannotReadOthersAnalysisStatus() throws Exception {
    UUID adjusterId = UUID.randomUUID();

    mockMvc.perform(get("/reports/{id}/analysis-status", reportId)
            .with(authenticatedAs(adjusterId, "CERTIFICATED_ADJUSTER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Q10 — 타인(USER)이 조회하면 403 FORBIDDEN이다")
  void strangerCannotReadAnalysisStatus() throws Exception {
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId)
            .with(authenticatedAs(UUID.randomUUID(), "USER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("사정사 계정이라도 본인이 만든 리포트면 소유자로서 조회된다(역할이 아니라 소유권 판정)")
  void adjusterOwnReportIsReadable() throws Exception {
    UUID adjusterId = UUID.randomUUID();
    UUID ownReportId = reportRepository.save(Report.createPending(
        adjusterId, null, null, AccidentType.TRAFFIC, "질문",
        "ANS-" + UUID.randomUUID().toString().substring(0, 12))).getId();

    mockMvc.perform(get("/reports/{id}/analysis-status", ownReportId)
            .with(authenticatedAs(adjusterId, "CERTIFICATED_ADJUSTER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("PROCESSING"));
  }

  @Test
  @DisplayName("Q11 — 존재하지 않는 리포트는 404 REPORT_NOT_FOUND다")
  void missingReportReturnsNotFound() throws Exception {
    mockMvc.perform(get("/reports/{id}/analysis-status", UUID.randomUUID())
            .with(authenticatedAs(ownerId, "USER")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
  }

  @Test
  @DisplayName("미인증 요청은 401이다(분석 상태도 인증 필수)")
  void anonymousRequestIsUnauthorized() throws Exception {
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId))
        .andExpect(status().isUnauthorized());
  }
}
