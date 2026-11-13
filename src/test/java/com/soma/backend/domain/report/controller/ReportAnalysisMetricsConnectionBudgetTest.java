package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.zaxxer.hikari.HikariDataSource;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportDraftMetricsViewFixture;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 결정 A 회귀 고정(design.md §11 T6) — 분석 근거 수치 조회는 요청 하나가 동시에 커넥션을 최대 1개만 쥔다.
 *
 * <p>Hikari 풀을 1개로 줄인 컨텍스트에서도 수치가 나와야 한다. {@code ReportAnalysisMetricsQueryService}나
 * {@code ReportAnalysisMetricsResolver}에 {@code @Transactional}을 붙이면 바깥 트랜잭션이 유일한 커넥션을 쥔 채
 * 리더(REQUIRES_NEW)가 두 번째 커넥션을 기다리다 타임아웃되고, 응답이 {@code UNAVAILABLE}로 떨어져 이 테스트가
 * 깨진다(2026-11-11 커넥션 풀 교착과 같은 구조). 짧은 대기 시간(5초)은 스케줄러 등의 순간 점유를 흡수하는 여유다.
 *
 * <p>이 클래스는 상세·목록 API를 부르지 않는다 — 그쪽은 바깥 트랜잭션 + REQUIRES_NEW 구조라 풀 1개에서 실패하는
 * 것이 정상이다. 풀 설정이 달라 컨텍스트가 하나 더 뜬다. 그 컨텍스트를 정리하겠다고 {@code @DirtiesContext}를
 * 붙이지 말 것 — test 프로파일이 {@code ddl-auto: create-drop}이라 컨텍스트가 닫히면 공유 test_db의 테이블이 전부
 * DROP돼 뒤에 도는 테스트 클래스가 깨진다.
 */
@SpringBootTest(properties = {
  "spring.datasource.hikari.maximum-pool-size=1",
  "spring.datasource.hikari.connection-timeout=5000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("분석 근거 수치 조회 커넥션 예산(풀 1개) 테스트")
class ReportAnalysisMetricsConnectionBudgetTest {

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private DataSource dataSource;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() throws Exception {
    // 전제 확인 — 풀이 정말 1개여야 "동시 1커넥션"을 검증한다(프로퍼티 이름이 바뀌면 조용히 15개로 돈다).
    // DataSource는 JDBC 관측 프록시로 감싸져 있을 수 있어 캐스트가 아니라 unwrap으로 꺼낸다.
    assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
    ownerId = UUID.randomUUID();
    Report report = Report.createPending(ownerId, null, null, AccidentType.DISABILITY, "질문",
        "RAB-" + UUID.randomUUID().toString().substring(0, 12));
    ReflectionTestUtils.setField(report, "confidenceLevel", "medium");
    reportId = reportRepository.save(report).getId();
    jdbcTemplate.update("INSERT INTO ai.report_drafts (report_id, draft) VALUES (?, ?::jsonb)",
        reportId, "{\"metrics\": " + ReportDraftMetricsViewFixture.GOLDEN_V1_METRICS + "}");
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai.report_drafts WHERE report_id = ?", reportId);
    reportRepository.deleteById(reportId);
  }

  @Test
  @DisplayName("풀이 1개여도 리포트 조회와 초안 조회가 겹치지 않아 available 수치가 나온다")
  void servesMetricsWithSingleConnectionPool() throws Exception {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");

    mockMvc.perform(get("/reports/{id}/analysis-metrics", reportId)
            .with(authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.available").value(true))
        .andExpect(jsonPath("$.data.unavailable_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.confidence.level").value("MEDIUM"));
  }
}
