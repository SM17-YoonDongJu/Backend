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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * #401 커넥션 획득 실패 degrade 검증(design.md §6 "커넥션 획득 실패" 행) — 모킹이 아니라 <b>실제 풀 고갈</b>로 재현한다.
 *
 * <p>리더 REQUIRES_NEW가 커넥션을 못 얻을 때 예외 체인의 가장 안쪽 원인은 SQLState가 없는
 * {@code SQLTransientConnectionException}이다 — 원인 없는 예외로 만든 단위 테스트로는 이 경로(SQLState null)를 타지 않아,
 * degrade 분류 중 NPE가 나 500이 되던 결함을 놓쳤다. 그래서 실제 Hikari 예외 체인으로 고정한다.
 *
 * <p><b>바깥 트랜잭션은 테스트 장치다(#406).</b> #406 전에는 목록·상세의 바깥 조회 트랜잭션과 {@code resolveAll}의
 * REQUIRES_NEW가 풀 2개를 다 쥐어 리더가 저절로 커넥션을 못 얻었다. 지금은 운영 조회 경로에 바깥 트랜잭션이 없어 요청 하나가
 * 커넥션을 1개만 쥐므로, 리더가 커넥션을 못 얻는 상황은 다른 요청들이 풀을 다 쓴 전역 고갈일 때만 생긴다. 그 상황을 만들려고
 * 풀을 1개로 줄이고, 테스트가 연 readOnly 트랜잭션이 그 하나를 쥔 채 요청을 보낸다. MockMvc는 같은 스레드에서 요청을
 * 처리하므로 카드 쿼리·리포트 재조회·상세 본 조회는 이 트랜잭션에 참여해 같은 커넥션을 쓰고, 리더의 REQUIRES_NEW만 새
 * 커넥션을 기다리다(Hikari 타임아웃 250ms) 시작에 실패한다.
 *
 * <p>확인하는 것: NEEDS_REUPLOAD 리포트(사유 off_domain 기록됨)의 목록·상세가 200이고 사유를 못 읽었을 때의 응답(사유
 * null·일반 문구)이며, 요청마다 {@code query_failed}가 1씩 오르고 {@code schema_missing}은 그대로다.
 *
 * <p>풀 설정이 달라 컨텍스트가 하나 더 뜬다. {@code @DirtiesContext}를 붙이지 말 것 — test 프로파일이
 * {@code ddl-auto: create-drop}이라 컨텍스트가 닫히면 공유 test_db의 테이블이 전부 DROP된다.
 */
@SpringBootTest(properties = {
  "spring.datasource.hikari.maximum-pool-size=1",
  "spring.datasource.hikari.connection-timeout=250"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("재업로드 사유 조회 커넥션 획득 실패(풀 고갈) degrade 테스트")
class ReuploadReasonPoolExhaustionIntegrationTest {

  private static final String GENERIC_MESSAGE = "업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private MeterRegistry meterRegistry;
  @Autowired
  private DataSource dataSource;
  @Autowired
  private PlatformTransactionManager transactionManager;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() throws Exception {
    // 전제 확인 — 풀이 정말 1개여야 바깥 트랜잭션이 쥔 동안 리더가 커넥션을 못 얻는다(프로퍼티 이름이 바뀌면 조용히 기본
    // 풀 크기로 돈다).
    assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
    ownerId = UUID.randomUUID();
    reportId = reportRepository.save(Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "RPE-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    jdbcTemplate.update("UPDATE reports SET status = 'NEEDS_REUPLOAD' WHERE id = ?", reportId);
    // 읽히기만 하면 DOMAIN_MISMATCH가 나올 행 — 일반 문구가 나오면 그건 조회 실패를 흡수한 결과다.
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

  private double reuploadDegraded(String cause) {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", cause).counter().count();
  }

  /**
   * 테스트가 연 readOnly 트랜잭션이 유일한 커넥션을 쥔 동안 {@code requests}를 실행한다(클래스 javadoc의 테스트 장치). 읽기만
   * 하므로 끝나면 롤백한다. 단언 실패({@link AssertionError})는 그대로 올라가고, MockMvc의 checked 예외만 감싼다.
   */
  private void whileTestTransactionHoldsTheOnlyConnection(MockMvcRequests requests) {
    TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
    readOnlyTransaction.setReadOnly(true);
    readOnlyTransaction.executeWithoutResult(transactionStatus -> {
      transactionStatus.setRollbackOnly();
      try {
        requests.perform();
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    });
  }

  /** 바깥 트랜잭션 안에서 보낼 MockMvc 요청들. */
  @FunctionalInterface
  private interface MockMvcRequests {
    void perform() throws Exception;
  }

  @Test
  @DisplayName("리더가 커넥션을 못 얻어도 목록·상세는 200 + 사유 없는 일반 응답이고 query_failed로 센다")
  void listAndDetailDegradeWhenReadersCannotGetConnection() {
    // Given
    double queryFailedBefore = reuploadDegraded("query_failed");
    double schemaMissingBefore = reuploadDegraded("schema_missing");

    // When & Then
    whileTestTransactionHoldsTheOnlyConnection(() -> {
      mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
          .andExpect(jsonPath("$.data.list[0].analysis_state").value("NEEDS_REUPLOAD"))
          .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value(nullValue()))
          .andExpect(jsonPath("$.data.list[0].analysis_failure_message").value(GENERIC_MESSAGE));
      mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.analysis_state").value("NEEDS_REUPLOAD"))
          .andExpect(jsonPath("$.data.analysis_failure_reason").value(nullValue()))
          .andExpect(jsonPath("$.data.analysis_failure_message").value(GENERIC_MESSAGE));
    });

    assertThat(reuploadDegraded("query_failed") - queryFailedBefore).isEqualTo(2.0);
    assertThat(reuploadDegraded("schema_missing")).isEqualTo(schemaMissingBefore);
  }
}
