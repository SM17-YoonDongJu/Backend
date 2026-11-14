package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
 * #401 운영 GRANT 재현 — {@code ai.claim_readiness}의 컬럼 한정 권한을 test_db에서 그대로 만들어 확인한다.
 *
 * <p>테스트 롤은 테이블 소유자라 평소엔 권한 문제가 드러나지 않는다. 하지만 소유자도 자기 테이블 권한을 회수할 수 있고
 * (다시 부여할 권리는 남는다) 그 뒤 AI 013과 같은 {@code GRANT SELECT (claim_id, report_id, missing_doc_types,
 * reupload_reason)}만 주면 운영 롤(app_owner·app_owner_prod)과 같은 조건이 된다. 그래서 두 가지를 실제 SQL로 확인한다.
 * <ol>
 *   <li>컬럼 한정 GRANT에서도 사유가 읽힌다 — 서브셀렉트가 열린 4개 컬럼 밖을 고르면(다른 컬럼·{@code *}) 여기서 42501로
 *       깨진다(운영에서만 나던 실패를 테스트가 잡는다).</li>
 *   <li>GRANT가 빠지면(013 적용 뒤 권한 누락) 200 + 지금과 같은 응답이고 {@code query_failed}로 센다(42501은 과도기가
 *       아니라 장애).</li>
 * </ol>
 *
 * <p>권한은 {@code @AfterEach}에서 무조건 되돌린다(테스트가 실패해도). 픽스처 행은 권한을 회수하기 전에 넣고, 되돌린 뒤
 * 이 테스트가 만든 행만 지운다. 회수와 복구 사이에 JVM이 강제로 죽으면(IDE 중단 등) 로컬 test_db에 권한이 회수된 채
 * 남아 다른 테스트가 42501로 깨진다 — 그때는 {@code GRANT ALL ON ai.claim_readiness TO test}로 되돌린다.
 *
 * <p><b>superuser 접속에선 건너뛴다.</b> superuser는 권한 검사를 아예 하지 않아 회수가 효과가 없다 — CI는 DB 컨테이너를
 * {@code POSTGRES_USER=test}로 띄워 {@code test}가 superuser라 여기서 건너뛰고, 로컬 test_db의 {@code test}(superuser
 * 아님)에서 돈다. CI에서도 서브셀렉트 컬럼 목록 자체는 {@code ClaimReadinessViewSubselectTest}가 문자열로 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("재업로드 사유 컬럼 한정 GRANT 재현 테스트")
class ReuploadReasonGrantIntegrationTest {

  private static final String GENERIC_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";
  private static final String DOMAIN_MESSAGE =
      "업로드하신 문서에서 보험 청구 서류를 찾지 못했어요. 보험증권·진단서 같은 청구 서류를 올려주세요.";

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
    Boolean superuser = jdbcTemplate.queryForObject(
        "select rolsuper from pg_roles where rolname = current_user", Boolean.class);
    assumeFalse(Boolean.TRUE.equals(superuser),
        "superuser 접속은 권한 검사를 건너뛰어 컬럼 한정 GRANT를 재현할 수 없다(CI의 POSTGRES_USER=test)");
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "RGI-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    jdbcTemplate.update("UPDATE reports SET status = 'NEEDS_REUPLOAD' WHERE id = ?", reportId);
    jdbcTemplate.update("""
        INSERT INTO ai.claim_readiness (claim_id, report_id, doc_total, missing_doc_types, status, reupload_reason)
        VALUES (?, ?, 2, '{}', 'blocked', 'off_domain')
        """, UUID.randomUUID().toString(), reportId);
  }

  @AfterEach
  void restoreGrantAndCleanUp() {
    jdbcTemplate.execute("GRANT ALL ON ai.claim_readiness TO CURRENT_USER");
    if (reportId == null) {
      // superuser라 setUp에서 건너뛴 경우 — 만든 행이 없다.
      return;
    }
    jdbcTemplate.update("DELETE FROM ai.claim_readiness WHERE report_id = ?", reportId);
    reportRepository.deleteById(reportId);
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private double degraded(String cause) {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", cause).counter().count();
  }

  @Test
  @DisplayName("AI 013과 같은 컬럼 한정 GRANT에서도 목록·상세·폴링이 DOMAIN_MISMATCH를 읽는다(서브셀렉트가 4개 컬럼 안)")
  void readsReasonUnderColumnLimitedGrant() throws Exception {
    jdbcTemplate.execute("REVOKE ALL ON ai.claim_readiness FROM CURRENT_USER");
    jdbcTemplate.execute("GRANT SELECT (claim_id, report_id, missing_doc_types, reupload_reason) "
        + "ON ai.claim_readiness TO CURRENT_USER");
    // 전제 확인 — 회수가 실제로 걸렸는지(열리지 않은 컬럼은 읽을 수 없다)
    assertThat(jdbcTemplate.queryForObject(
        "select has_column_privilege('ai.claim_readiness', 'status', 'SELECT')", Boolean.class)).isFalse();
    double queryFailedBefore = degraded("query_failed");

    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value("DOMAIN_MISMATCH"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_message").value(DOMAIN_MESSAGE));
    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_failure_reason").value("DOMAIN_MISMATCH"));
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failure_reason").value("DOMAIN_MISMATCH"));
    assertThat(degraded("query_failed")).isEqualTo(queryFailedBefore);
  }

  @Test
  @DisplayName("GRANT가 없으면(42501) 목록·상세·폴링 모두 200 + 지금과 같은 응답이고 query_failed로 센다")
  void degradesWhenGrantIsMissing() throws Exception {
    jdbcTemplate.execute("REVOKE ALL ON ai.claim_readiness FROM CURRENT_USER");
    double queryFailedBefore = degraded("query_failed");
    double schemaMissingBefore = degraded("schema_missing");

    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("NEEDS_REUPLOAD"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_message").value(GENERIC_MESSAGE));
    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.analysis_failure_message").value(GENERIC_MESSAGE));
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.failure_reason").value(nullValue()))
        .andExpect(jsonPath("$.data.failure_message").value(GENERIC_MESSAGE));

    assertThat(degraded("query_failed") - queryFailedBefore).isEqualTo(3.0);
    assertThat(degraded("schema_missing")).isEqualTo(schemaMissingBefore);
  }
}
