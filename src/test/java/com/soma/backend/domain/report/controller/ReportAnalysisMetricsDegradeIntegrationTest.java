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
 * {@code ai.report_drafts}가 없을 때의 degrade 검증(design.md §11 T7) — 테이블을 <b>실제로 지워</b> 진짜 SQL 실패
 * (SQLState 42P01)로 재현한다(모킹이 아니라야 REQUIRES_NEW 격리와 예외 체인의 SQLState가 확인된다).
 *
 * <p>확인하는 것: 수치 API는 500이 아니라 200 + {@code UNAVAILABLE}이고 {@code table_missing} 카운터가 오른다.
 * 같은 상황에서 상세·목록 API는 영향 없이 200이다. 다른 {@code ai} 계약 테이블은 그대로 두고 이 테이블만 지우며,
 * 클래스 종료 시 계약 스키마를 원복한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", statements = "DROP TABLE IF EXISTS ai.report_drafts",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS)
@DisplayName("ai.report_drafts 부재 시 분석 근거 수치 degrade 테스트")
class ReportAnalysisMetricsDegradeIntegrationTest {

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
    // 전제 확인 — @Sql의 DROP이 빠지면 초안이 없는 새 리포트는 어차피 NO_DRAFT라 원인 구분 없이 통과해 버린다.
    assertThat(jdbcTemplate.queryForObject("select to_regclass('ai.report_drafts')::text", String.class)).isNull();
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "RAD-" + UUID.randomUUID().toString().substring(0, 12))).getId();
  }

  @AfterEach
  void cleanUp() {
    // REQUIRES_NEW 가시성 때문에 커밋 경로로 검증하므로 이 테스트가 만든 행만 직접 지운다.
    reportRepository.deleteById(reportId);
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private double tableMissingCount() {
    return meterRegistry.get("report.metrics.unavailable").tag("cause", "table_missing").counter().count();
  }

  @Test
  @DisplayName("수치 API는 200 + UNAVAILABLE(table_missing +1)이고, 상세·목록 API는 영향 없이 200이다")
  void metricsDegradeWithoutAffectingOtherReportApis() throws Exception {
    double before = tableMissingCount();

    mockMvc.perform(get("/reports/{id}/analysis-metrics", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(false))
        .andExpect(jsonPath("$.data.unavailable_reason").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.data.version").value(nullValue()))
        .andExpect(jsonPath("$.data.confidence").value(nullValue()));
    assertThat(tableMissingCount() - before).isEqualTo(1.0);

    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.report_id").value(reportId.toString()));
    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()));
  }
}
