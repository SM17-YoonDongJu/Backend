package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import javax.sql.DataSource;

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

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * #401 커넥션 예산 회귀 고정(design.md §9-2 I5), #406에서 풀 3개 → 1개로 조였다 — 재업로드 원인 조회를 더해도 목록·상세·
 * 폴링 요청 하나가 동시에 쥐는 커넥션은 1개다. 바깥 트랜잭션이 없고, 본 조회·리포트 재조회·리더 REQUIRES_NEW가 차례로
 * 하나씩 커넥션을 얻고 반납한다(재업로드 리포트라 리더가 4개까지 돈다).
 *
 * <p>Hikari 풀을 1개로 줄인 컨텍스트에서도 사유가 나와야 한다. 누가 리더 안에서 REQUIRES_NEW를 한 단계 더 중첩하거나
 * 조회 서비스·{@code resolveAll}에 트랜잭션을 다시 붙이면 두 번째 커넥션을 5초 기다리다 실패해, 응답이 500이 되거나
 * 재업로드 원인이 일반 문구로 degrade돼 이 테스트가 깨진다(2026-11-11·11-16 커넥션 풀 교착과 같은 구조).
 *
 * <p>풀 설정이 {@code ReportQueryConnectionBudgetTest}·{@code ReportAnalysisMetricsConnectionBudgetTest}와 같아
 * 컨텍스트를 공유한다. 그 컨텍스트를 정리하겠다고 {@code @DirtiesContext}를 붙이지 말 것 — test 프로파일이
 * {@code ddl-auto: create-drop}이라 컨텍스트가 닫히면 공유 test_db의 테이블이 전부 DROP된다.
 */
@SpringBootTest(properties = {
  "spring.datasource.hikari.maximum-pool-size=1",
  "spring.datasource.hikari.connection-timeout=5000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("재업로드 사유 조회 커넥션 예산(풀 1개) 테스트")
class ReuploadReasonConnectionBudgetTest {

  private static final String DOMAIN_MESSAGE =
      "업로드하신 문서에서 보험 청구 서류를 찾지 못했어요. 보험증권·진단서 같은 청구 서류를 올려주세요.";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private DataSource dataSource;
  @Autowired
  private MeterRegistry meterRegistry;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() throws Exception {
    // 전제 확인 — 풀이 정말 1개여야 "동시 1커넥션"을 검증한다(프로퍼티 이름이 바뀌면 조용히 기본 풀 크기로 돈다).
    // DataSource는 JDBC 관측 프록시로 감싸져 있을 수 있어 캐스트가 아니라 unwrap으로 꺼낸다.
    assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, null,
        "질문", "RCB-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    jdbcTemplate.update("UPDATE reports SET status = 'NEEDS_REUPLOAD' WHERE id = ?", reportId);
    jdbcTemplate.update("""
        INSERT INTO ai.claim_readiness (claim_id, report_id, doc_total, missing_doc_types, status, reupload_reason)
        VALUES (?, ?, 2, '{}', 'blocked', 'off_domain')
        """, UUID.randomUUID().toString(), reportId);
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai.claim_readiness WHERE report_id = ?", reportId);
    reportRepository.deleteById(reportId);
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private double queryFailedCount() {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", "query_failed").counter().count();
  }

  @Test
  @DisplayName("풀이 1개여도 목록·상세·폴링이 커넥션을 기다리지 않고 DOMAIN_MISMATCH를 낸다(degrade 없음)")
  void servesReuploadReasonWithinSingleConnection() throws Exception {
    double queryFailedBefore = queryFailedCount();

    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value("DOMAIN_MISMATCH"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_message").value(DOMAIN_MESSAGE));
    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_failure_reason").value("DOMAIN_MISMATCH"));
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failure_reason").value("DOMAIN_MISMATCH"));

    assertThat(queryFailedCount()).isEqualTo(queryFailedBefore);
  }
}
