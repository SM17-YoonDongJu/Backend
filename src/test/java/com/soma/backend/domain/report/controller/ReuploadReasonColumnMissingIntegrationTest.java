package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import io.micrometer.core.instrument.MeterRegistry;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * AI 013이 아직 적용되지 않은 환경(Backend 선배포)의 degrade 검증(design.md §9-2 I3) — {@code reupload_reason} 컬럼을
 * <b>실제로 지워</b> 진짜 SQL 실패(SQLState 42703)로 재현한다. 모킹이 아니라야 REQUIRES_NEW 격리와 예외 체인의 SQLState가
 * 확인된다.
 *
 * <p>확인하는 것: NEEDS_REUPLOAD 리포트의 목록·상세·폴링이 전부 200이고 지금과 같은 응답(사유 null·일반 문구)이며,
 * 요청마다 {@code analysis.reupload_reason.degraded{cause=schema_missing}}이 오른다(42703을 과도기로 분류).
 *
 * <p>클래스 종료 시 테이블을 지우고 계약 스키마로 다시 만든다 — {@code CREATE TABLE IF NOT EXISTS}만으로는 지운 컬럼이
 * 돌아오지 않아서 두 단계({@code DROP} → 스크립트)를 이 순서로 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql",
    statements = "ALTER TABLE ai.claim_readiness DROP COLUMN IF EXISTS reupload_reason",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = "DROP TABLE IF EXISTS ai.claim_readiness", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
@DisplayName("재업로드 사유 컬럼(013) 미적용 시 degrade 테스트")
class ReuploadReasonColumnMissingIntegrationTest {

  private static final String GENERIC_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private MeterRegistry meterRegistry;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    // 전제 확인 — @Sql의 DROP COLUMN이 빠지면 행이 없는 리포트는 어차피 일반 문구라 원인 구분 없이 통과해 버린다.
    assertThat(jdbcTemplate.queryForObject("""
        select count(*) from information_schema.columns
         where table_schema = 'ai' and table_name = 'claim_readiness' and column_name = 'reupload_reason'
        """, Integer.class)).isZero();
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "RCM-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    jdbcTemplate.update("UPDATE reports SET status = 'NEEDS_REUPLOAD' WHERE id = ?", reportId);
    // 사유 컬럼만 없는 상태 — 010 행 자체는 있어도 사유를 읽을 수 없다.
    jdbcTemplate.update("""
        INSERT INTO ai.claim_readiness (claim_id, report_id, doc_total, missing_doc_types, status)
        VALUES (?, ?, 2, '{policy}', 'blocked')
        """, UUID.randomUUID().toString(), reportId);
  }

  @AfterEach
  void cleanUp() {
    // REQUIRES_NEW 가시성 때문에 커밋 경로로 검증하므로 이 테스트가 만든 행만 직접 지운다.
    jdbcTemplate.update("DELETE FROM ai.claim_readiness WHERE report_id = ?", reportId);
    reportRepository.deleteById(reportId);
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private double schemaMissingCount() {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", "schema_missing").counter().count();
  }

  private double queryFailedCount() {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", "query_failed").counter().count();
  }

  @Test
  @DisplayName("목록·상세·폴링 모두 200 + 사유 null·일반 문구이고, 요청마다 schema_missing이 1씩 오른다(query_failed 아님)")
  void everyEndpointDegradesToCurrentResponse() throws Exception {
    double schemaMissingBefore = schemaMissingCount();
    double queryFailedBefore = queryFailedCount();

    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_message").value(GENERIC_MESSAGE));
    assertThat(schemaMissingCount() - schemaMissingBefore).isEqualTo(1.0);

    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.analysis_failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.analysis_failure_message").value(GENERIC_MESSAGE));
    assertThat(schemaMissingCount() - schemaMissingBefore).isEqualTo(2.0);

    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.failure_message").value(GENERIC_MESSAGE))
        .andExpect(jsonPath("$.data.reupload_guidance").value("RECOMMENDED"));
    assertThat(schemaMissingCount() - schemaMissingBefore).isEqualTo(3.0);
    assertThat(queryFailedCount()).isEqualTo(queryFailedBefore);
  }
}
