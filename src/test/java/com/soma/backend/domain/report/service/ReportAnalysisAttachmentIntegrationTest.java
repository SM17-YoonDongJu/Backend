package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * 목록·상세에 분석 상태를 붙이는 경로의 통합 검증(design.md §15 Q3·Q13).
 *
 * <p><b>{@code @Transactional}을 쓰지 않는다</b> — 분석 상태 배치 조회({@code resolveAll})가
 * {@code REQUIRES_NEW}라 별도 커넥션의 새 트랜잭션에서 돌기 때문에, 테스트 트랜잭션에 갇힌(미커밋) 시드는
 * 아예 보이지 않는다. 실제 운영과 같은 커밋 경로로 검증하고 커밋한 데이터는 {@code @AfterEach}에서 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("목록·상세 분석 상태 부착 통합 테스트")
class ReportAnalysisAttachmentIntegrationTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 12, 14, 3, 11);

  @Autowired
  private ReportQueryService reportQueryService;
  @Autowired
  private ReportAnalysisStatusQueryService reportAnalysisStatusQueryService;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** 배치 조회가 페이지당 1회만 도는지(N+1 부재) 세기 위한 스파이. 실제 쿼리는 그대로 실행된다. */
  @MockitoSpyBean
  private OcrJobFailureViewRepository ocrJobFailureViewRepository;

  private UUID ownerId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures");
    reportRepository.deleteAll();
  }

  private UUID saveReport(boolean withAiDraft) {
    Report report = Report.createPending(ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "ANA-" + UUID.randomUUID().toString().substring(0, 12));
    if (withAiDraft) {
      ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("상해후유장해"));
    }
    return reportRepository.save(report).getId();
  }

  private void insertFailure(UUID reportId, String failureClass, boolean terminal) {
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, report_id, attachment_id, failure_class, error_type, attempts, terminal,
           first_failed_at, last_failed_at)
        VALUES (?, ?, ?, ?, 'ValueError', 2, ?, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(), failureClass, terminal, FAILED_AT, FAILED_AT);
  }

  private Map<UUID, ReportCardListResponse.Card> cardsByReportId() {
    return reportQueryService.getUserReports(ownerId, null, 1, 20).list().stream()
        .collect(Collectors.toMap(ReportCardListResponse.Card::reportId, Function.identity()));
  }

  @Test
  @DisplayName("Q13 — 실패 리포트가 여러 건이어도 실패 저널 배치 조회는 페이지당 1회이고 카드 행이 복제되지 않는다")
  void listAttachesAnalysisWithSingleBatchQuery() {
    // Given: 실패 리포트 3건, 그중 하나는 실패 문서가 3건(조인했다면 카드가 3배로 늘어난다)
    UUID first = saveReport(false);
    UUID second = saveReport(false);
    UUID third = saveReport(false);
    insertFailure(first, "unreadable_file", true);
    insertFailure(first, "ocr_error", true);
    insertFailure(first, "masking_residual", true);
    insertFailure(second, "ocr_error", true);
    insertFailure(third, "schema_invalid", true);

    // When
    ReportCardListResponse response = reportQueryService.getUserReports(ownerId, null, 1, 20);

    // Then: 리포트 수만큼만 카드가 나오고(행 복제 없음), 저널 조회는 리포트 수와 무관하게 1회다
    assertThat(response.list()).hasSize(3);
    assertThat(response.list()).extracting(ReportCardListResponse.Card::reportId)
        .containsExactlyInAnyOrder(first, second, third);
    verify(ocrJobFailureViewRepository, times(1)).findAllByReportIdInAndTerminalIsTrue(anyCollection());
  }

  @Test
  @DisplayName("목록 카드의 분석 필드 — 확정 실패는 FAILED+대표 사유, 일시 실패는 PROCESSING, AI 초안은 COMPLETED")
  void listCardsCarryAnalysisState() {
    // Given
    UUID failed = saveReport(false);
    UUID processing = saveReport(false);
    UUID completed = saveReport(true);
    insertFailure(failed, "unreadable_file", true);
    insertFailure(failed, "masking_residual", true);
    insertFailure(processing, "ocr_error", false);
    // Q3(E3) — 성공한 리포트에 실패 행이 남아 있어도 사용자에게는 실패가 보이면 안 된다.
    insertFailure(completed, "ocr_error", true);

    // When
    Map<UUID, ReportCardListResponse.Card> cards = cardsByReportId();

    // Then
    assertThat(cards.get(failed).analysisState()).isEqualTo("FAILED");
    assertThat(cards.get(failed).analysisFailureReason()).isEqualTo("MASKING_RESIDUAL");
    assertThat(cards.get(failed).analysisFailureMessage()).isEqualTo("문서를 검토 중입니다");
    assertThat(cards.get(processing).analysisState()).isEqualTo("PROCESSING");
    assertThat(cards.get(processing).analysisFailureReason()).isNull();
    assertThat(cards.get(processing).analysisFailureMessage()).isNull();
    assertThat(cards.get(completed).analysisState()).isEqualTo("COMPLETED");
    assertThat(cards.get(completed).analysisFailureReason()).isNull();
  }

  @Test
  @DisplayName("상세 응답에도 같은 분석 상태 3필드가 실린다")
  void detailCarriesAnalysisState() {
    // Given
    UUID reportId = saveReport(false);
    insertFailure(reportId, "unreadable_file", true);

    // When
    CustomerReportDetailResponse detail = reportQueryService.getReportDetail(ownerId, "USER", reportId);

    // Then
    assertThat(detail.analysisState()).isEqualTo("FAILED");
    assertThat(detail.analysisFailureReason()).isEqualTo("UNREADABLE_FILE");
    assertThat(detail.analysisFailureMessage()).isEqualTo("파일을 읽을 수 없어요. 다시 업로드해주세요");
  }

  @Test
  @DisplayName("resolveAll — 요청한 리포트 전부에 상태를 채워 주고 실패 없는 리포트는 PROCESSING이다")
  void resolveAllReturnsStateForEveryRequestedReport() {
    // Given
    UUID failed = saveReport(false);
    UUID clean = saveReport(false);
    insertFailure(failed, "ocr_error", true);

    // When
    Map<UUID, ReportAnalysis> analyses =
        reportAnalysisStatusQueryService.resolveAll(Arrays.asList(failed, clean, failed, null));

    // Then: 중복·null은 걸러지고 요청한 리포트만 담긴다
    assertThat(analyses).containsOnlyKeys(failed, clean);
    assertThat(analyses.get(failed).isFailed()).isTrue();
    assertThat(analyses.get(clean).state().name()).isEqualTo("PROCESSING");
  }
}
