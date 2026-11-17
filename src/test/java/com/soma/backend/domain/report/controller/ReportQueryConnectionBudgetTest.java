package com.soma.backend.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.service.ReportAnalysisResolver;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * #406 회귀 고정 — 리포트 목록·받은 제안·상세·분석 상태 조회는 요청 하나가 동시에 커넥션을 최대 1개만 쥔다.
 *
 * <p>#406 전에는 두 조회 서비스의 클래스 트랜잭션이 커넥션을 쥔 채 {@code resolveAll}(REQUIRES_NEW)과 리더
 * (REQUIRES_NEW)가 커넥션을 더 요구해 요청 하나가 최대 3개를 쥐었고, Hikari 풀 15개에서 동시 요청 8건이면 서로 다음
 * 커넥션을 기다리며 교착했다(2026-11-11·11-16 dev 부하테스트). 지금은 본 조회·리포트 재조회·리더가 겹치지 않고 차례로
 * 하나씩 커넥션을 얻고 반납한다.
 *
 * <p>Hikari 풀을 1개로 줄인 컨텍스트에서도 네 API가 OCR 확정 실패를 FAILED로 정확히 내야 한다. 누가 두 서비스나
 * {@code ReportAnalysisResolver#resolveAll}에 다시 {@code @Transactional}을 붙이면(어떤 전파든) 두 번째 커넥션을
 * 5초 기다리다 실패해 응답이 500이 되거나 판정이 PROCESSING으로 낮아져 이 테스트가 깨진다. 짧은 대기 시간(5초)은
 * 스케줄러 등의 순간 점유를 흡수하는 여유다.
 *
 * <p>상세는 채택된 검수(ACCEPTED)를 시드해 쟁점 의견을 단언한다 — 의견은 {@code ReportReview}의 지연 로딩 컬렉션을
 * 풀어야 나오므로, 이 조립이 트랜잭션 밖으로 새면 {@code LazyInitializationException}으로 깨진다. 대부분의 상세
 * 테스트는 테스트 트랜잭션 안에서 돌아 이 실수를 잡지 못한다.
 *
 * <p>{@code @Transactional}을 쓰지 않는다 — 리더가 REQUIRES_NEW라 테스트 트랜잭션의 미커밋 시드를 못 본다. 커밋한
 * 데이터는 {@code @AfterEach}에서 지운다. 풀 설정이 다른 테스트와 같아(분석 근거 수치·재업로드 사유 예산 테스트)
 * 컨텍스트를 공유한다. 이 컨텍스트를 정리하겠다고 {@code @DirtiesContext}를 붙이지 말 것 — test 프로파일이
 * {@code ddl-auto: create-drop}이라 컨텍스트가 닫히면 공유 test_db의 테이블이 전부 DROP돼 뒤에 도는 테스트 클래스가
 * 깨진다.
 */
@SpringBootTest(properties = {
  "spring.datasource.hikari.maximum-pool-size=1",
  "spring.datasource.hikari.connection-timeout=5000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("리포트 조회 커넥션 예산(풀 1개) 테스트")
class ReportQueryConnectionBudgetTest {

  private static final String ADJUSTER_OPINION = "사정사 의견: 진단서 기준으로 보장 대상이다";
  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 11, 16, 9, 30, 0);

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportIssueRepository reportIssueRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private ReportAnalysisResolver reportAnalysisResolver;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @Autowired
  private DataSource dataSource;
  @Autowired
  private MeterRegistry meterRegistry;

  private UUID ownerId;
  private UUID reportId;
  private UUID issueId;
  private UUID reviewId;

  @BeforeEach
  void setUp() throws Exception {
    // 전제 확인 — 풀이 정말 1개여야 "동시 1커넥션"을 검증한다(프로퍼티 이름이 바뀌면 조용히 15개로 돈다).
    // DataSource는 JDBC 관측 프록시로 감싸져 있을 수 있어 캐스트가 아니라 unwrap으로 꺼낸다.
    assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
    ownerId = UUID.randomUUID();
    // AI 초안이 없는(applicable_guarantees null) 리포트라 OCR 확정 실패가 있으면 FAILED로 판정된다.
    reportId = reportRepository.save(Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, null,
        "질문", "RQB-" + UUID.randomUUID().toString().substring(0, 12))).getId();
    issueId = saveIssue(reportId);
    // 받은 제안 목록은 거절되지 않은 검수가 있어야 행이 나오고, 상세는 채택(ACCEPTED)이어야 쟁점 의견을 싣는다.
    reviewId = saveAcceptedReviewWithOpinion(reportId, issueId);
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, report_id, attachment_id, failure_class, error_type, attempts, terminal,
           first_failed_at, last_failed_at)
        VALUES (?, ?, ?, 'unreadable_file', 'ValueError', 2, true, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(), FAILED_AT, FAILED_AT);
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures WHERE report_id = ?", reportId);
    // 검수를 지우면 쟁점 오버레이(report_issues_reviews)도 cascade로 함께 지워진다.
    reportReviewRepository.deleteById(reviewId);
    reportIssueRepository.deleteById(issueId);
    reportRepository.deleteById(reportId);
  }

  private UUID saveIssue(UUID targetReportId) {
    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "reportId", targetReportId);
    ReflectionTestUtils.setField(issue, "title", "보장 대상 여부");
    ReflectionTestUtils.setField(issue, "description", "AI 초안 설명");
    ReflectionTestUtils.setField(issue, "aiStatus", "CONFIRMED");
    return reportIssueRepository.save(issue).getId();
  }

  private UUID saveAcceptedReviewWithOpinion(UUID targetReportId, UUID aiIssueId) {
    ReportReview review = ReportReview.create(targetReportId, UUID.randomUUID());
    ReflectionTestUtils.setField(review, "status", ReviewStatus.ACCEPTED);
    review.getIssues().add(ReportReviewIssue.create(aiIssueId, null, null, null, IssueReviewStatus.MODIFIED,
        ADJUSTER_OPINION, "진단서 근거 보강", null));
    return reportReviewRepository.save(review).getId();
  }

  private RequestPostProcessor authenticatedAsOwner() {
    CustomUserDetails principal = new CustomUserDetails(ownerId, "USER");
    return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  private double journalDegraded(String journal) {
    return meterRegistry.get("analysis.journal.degraded").tag("journal", journal).counter().count();
  }

  @Test
  @DisplayName("풀이 1개여도 내 리포트 목록은 커넥션을 기다리지 않고 OCR 확정 실패를 FAILED로 낸다(degrade 없음)")
  void listServesFailedWithinSingleConnection() throws Exception {
    // Given
    double ocrDegradedBefore = journalDegraded("ocr_job");
    double reportJobDegradedBefore = journalDegraded("report_job");

    // When & Then
    mockMvc.perform(get("/reports").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("FAILED"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value("UNREADABLE_FILE"));
    assertThat(journalDegraded("ocr_job")).isEqualTo(ocrDegradedBefore);
    assertThat(journalDegraded("report_job")).isEqualTo(reportJobDegradedBefore);
  }

  @Test
  @DisplayName("풀이 1개여도 받은 제안 목록은 커넥션을 기다리지 않고 FAILED를 낸다(degrade 없음)")
  void receivedProposalsServeFailedWithinSingleConnection() throws Exception {
    // Given
    double ocrDegradedBefore = journalDegraded("ocr_job");
    double reportJobDegradedBefore = journalDegraded("report_job");

    // When & Then
    mockMvc.perform(get("/me/received-proposals").with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.list[0].analysis_state").value("FAILED"))
        .andExpect(jsonPath("$.data.list[0].analysis_failure_reason").value("UNREADABLE_FILE"));
    assertThat(journalDegraded("ocr_job")).isEqualTo(ocrDegradedBefore);
    assertThat(journalDegraded("report_job")).isEqualTo(reportJobDegradedBefore);
  }

  @Test
  @DisplayName("풀이 1개여도 상세는 FAILED와 채택 검수의 쟁점 의견을 함께 낸다(본 조회와 분석 판정이 겹치지 않음)")
  void detailServesFailedAndAdjusterOpinionWithinSingleConnection() throws Exception {
    // Given
    double ocrDegradedBefore = journalDegraded("ocr_job");
    double reportJobDegradedBefore = journalDegraded("report_job");

    // When & Then
    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("FAILED"))
        .andExpect(jsonPath("$.data.analysis_failure_reason").value("UNREADABLE_FILE"))
        .andExpect(jsonPath("$.data.issue[0].opinion").value(ADJUSTER_OPINION));
    assertThat(journalDegraded("ocr_job")).isEqualTo(ocrDegradedBefore);
    assertThat(journalDegraded("report_job")).isEqualTo(reportJobDegradedBefore);
  }

  @Test
  @DisplayName("풀이 1개여도 분석 상태 폴링은 커넥션을 기다리지 않고 FAILED와 대표 사유를 낸다")
  void analysisStatusServesFailedWithinSingleConnection() throws Exception {
    // Given
    double reportJobDegradedBefore = journalDegraded("report_job");

    // When & Then
    mockMvc.perform(get("/reports/{id}/analysis-status", reportId).with(authenticatedAsOwner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.analysis_state").value("FAILED"))
        .andExpect(jsonPath("$.data.failure_reason").value("UNREADABLE_FILE"));
    assertThat(journalDegraded("report_job")).isEqualTo(reportJobDegradedBefore);
  }

  @Test
  @DisplayName("풀이 고갈돼 리포트 재조회가 커넥션을 못 얻으면 resolveAll은 DataAccessException을 던진다(호출자가 흡수)")
  void resolveAllThrowsDataAccessExceptionWhenNoConnectionIsAvailable() throws Exception {
    // Given: 테스트 스레드가 유일한 커넥션을 쥐어 전역 풀 고갈을 재현한다
    try (Connection held = dataSource.getConnection()) {
      // When & Then: 목록·상세의 analysesOrDegrade는 DataAccessException만 흡수한다 — 트랜잭션 시작 실패
      // (CannotCreateTransactionException)로 나오면 흡수되지 않고 500이 된다(#406 전의 구멍)
      assertThatThrownBy(() -> reportAnalysisResolver.resolveAll(List.of(reportId)))
          .isInstanceOf(DataAccessException.class)
          .hasRootCauseInstanceOf(SQLTransientConnectionException.class);
    }
  }
}
