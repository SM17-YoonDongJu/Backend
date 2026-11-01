package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 리포트 생성 실패 저널({@code ai.report_job_failures}, AI 마이그레이션 012)이 아직 없는 환경의 degrade 검증.
 *
 * <p>012는 AI report 워커가 기동할 때 환경별로 적용하므로, OCR 저널(008)은 있는데 012만 없는 상태로 Backend가
 * 먼저 배포될 수 있다. 그 상태를 <b>테이블을 실제로 지워</b> 재현한다(모킹이 아니라 진짜 SQL 실패라야
 * {@code REQUIRES_NEW} 분리가 의미 있는지 확인된다).
 *
 * <p>확인하는 것: OCR 저널 전체가 없을 때({@link AnalysisStatusDegradeIntegrationTest})와 달리 <b>전용 폴링
 * 엔드포인트도 500이 아니라 200</b>이고, <b>OCR 확정 실패는 그대로 FAILED</b>로 보인다 — 저널마다 따로 격리하므로
 * 한쪽 부재가 다른 쪽 판정을 무너뜨리지 않는다. 클래스 종료 시 계약 스키마를 원복한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", statements = "DROP TABLE IF EXISTS ai.report_job_failures",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
@DisplayName("리포트 생성 실패 저널(012) 미적용 시 degrade 테스트")
class ReportJobJournalDegradeIntegrationTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 11, 1, 9, 30, 0);

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    // 전제 확인 — @Sql의 DROP이 빠지면 새 리포트는 어차피 PROCESSING이라 아래 테스트가 그냥 통과해 버린다.
    assertThat(jdbcTemplate.queryForObject("select to_regclass('ai.report_job_failures')::text", String.class))
        .isNull();
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "RJD-" + UUID.randomUUID().toString().substring(0, 12))).getId();
  }

  @AfterEach
  void cleanUp() {
    // REQUIRES_NEW 가시성 때문에 커밋 경로로 검증하므로 롤백 대신 직접 지운다.
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures");
    reportRepository.deleteAll();
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private void insertOcrTerminalFailure(String failureClass) {
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, report_id, attachment_id, failure_class, error_type, attempts, terminal,
           first_failed_at, last_failed_at)
        VALUES (?, ?, ?, ?, 'ValueError', 1, true, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(), failureClass, FAILED_AT, FAILED_AT);
  }

  @Test
  @DisplayName("전용 analysis-status 엔드포인트는 500이 아니라 200 + PROCESSING이다(Backend가 012보다 먼저 배포된 환경)")
  void dedicatedEndpointDegradesInsteadOfFailing() throws Exception {
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("PROCESSING"))
        .andExpect(jsonPath("$.data.failed_documents").isEmpty());
  }

  @Test
  @DisplayName("OCR 확정 실패는 리포트 생성 저널이 없어도 폴링·목록에서 그대로 FAILED다(저널별 독립 격리)")
  void ocrFailureSurvivesMissingReportJobJournal() throws Exception {
    // Given
    insertOcrTerminalFailure("unreadable_file");

    // When & Then
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("FAILED"))
        .andExpect(jsonPath("$.data.failure_reason").value("UNREADABLE_FILE"));
    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("FAILED"));
  }
}
