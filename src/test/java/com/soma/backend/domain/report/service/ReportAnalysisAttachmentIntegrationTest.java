package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ReportAnalysisStatusResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.repository.ClaimReadinessViewRepository;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.OcrResultViewRepository;
import com.soma.backend.domain.report.repository.ReportJobFailureViewRepository;
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
  private ReportAnalysisResolver reportAnalysisResolver;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** 배치 조회가 페이지당 1회만 도는지(N+1 부재) 세기 위한 스파이. 실제 쿼리는 그대로 실행된다. */
  @MockitoSpyBean
  private OcrJobFailureViewRepository ocrJobFailureViewRepository;
  @MockitoSpyBean
  private ReportJobFailureViewRepository reportJobFailureViewRepository;
  /** #401 — 재업로드 입력(ai.claim_readiness·ai.ocr_results)이 NEEDS_REUPLOAD 리포트에만 조회되는지 세는 스파이. */
  @MockitoSpyBean
  private ClaimReadinessViewRepository claimReadinessViewRepository;
  @MockitoSpyBean
  private OcrResultViewRepository ocrResultViewRepository;

  private UUID ownerId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
  }

  @AfterEach
  void cleanUp() {
    // ai.claim_readiness는 이 테스트 소유자의 리포트에 단 행만 지운다(리포트를 지우기 전에).
    jdbcTemplate.update(
        "DELETE FROM ai.claim_readiness WHERE report_id IN (SELECT id FROM reports WHERE user_id = ?)", ownerId);
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures");
    jdbcTemplate.update("DELETE FROM ai.report_job_failures");
    jdbcTemplate.update("DELETE FROM ai.ocr_results");
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

  /**
   * 리포트 생성 실패 저널 행 삽입 — ai.report_job_failures(AI 012). 리더가 REQUIRES_NEW(별도 커넥션)라 커밋된
   * 행만 보이므로, 이 저널을 읽는 폴링·목록·상세 경로는 이 클래스(커밋 경로)에서 검증한다.
   */
  private void insertReportJobFailure(UUID reportId, boolean terminal) {
    jdbcTemplate.update("""
        INSERT INTO ai.report_job_failures
          (id, report_id, job_id, failure_class, error_type, attempts, terminal, first_failed_at, last_failed_at)
        VALUES (?, ?, ?, 'llm_unavailable', 'AiClientError', 3, ?, ?, ?)
        """,
        UUID.randomUUID(), reportId, UUID.randomUUID(), terminal, FAILED_AT, FAILED_AT);
  }

  /** 품질 미달(needs_reupload) 판정 행 삽입 — ai.ocr_results, PR #66 GRANT 대상. */
  private UUID insertNeedsReuploadDocument(UUID reportId) {
    UUID attachmentId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_results (id, report_id, claim_id, attachment_id, doc_type, doc_index, ocr_quality)
        VALUES (?, ?, 'claim-1', ?, 'diagnosis', 0, 'needs_reupload')
        """,
        UUID.randomUUID(), reportId, attachmentId);
    return attachmentId;
  }

  /** 청구 판정 행 삽입 — ai.claim_readiness(AI 010·013). */
  private void insertReadiness(UUID reportId, String reuploadReason) {
    jdbcTemplate.update("""
        INSERT INTO ai.claim_readiness (claim_id, report_id, doc_total, missing_doc_types, status, reupload_reason)
        VALUES (?, ?, 2, '{}', 'blocked', ?)
        """, UUID.randomUUID().toString(), reportId, reuploadReason);
  }

  /** AI 워커가 원시 SQL로 세팅하는 종료 상태를 같은 값으로 만든다(전이표를 거치지 않는 진입). */
  private void markStatus(UUID reportId, String status) {
    jdbcTemplate.update("UPDATE reports SET status = ? WHERE id = ?", status, reportId);
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
    insertReportJobFailure(second, true);
    insertReportJobFailure(third, true);

    // When
    ReportCardListResponse response = reportQueryService.getUserReports(ownerId, null, 1, 20);

    // Then: 리포트 수만큼만 카드가 나오고(행 복제 없음), 두 저널 조회 모두 리포트 수와 무관하게 1회다
    assertThat(response.list()).hasSize(3);
    assertThat(response.list()).extracting(ReportCardListResponse.Card::reportId)
        .containsExactlyInAnyOrder(first, second, third);
    verify(ocrJobFailureViewRepository, times(1)).findAllByReportIdInAndTerminalIsTrue(anyCollection());
    verify(reportJobFailureViewRepository, times(1)).findAllByReportIdIn(anyCollection());
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
  @DisplayName("⑤ — 리포트 생성 확정 실패는 폴링·목록·상세 모두 FAILED(UNKNOWN·HOLD)이고 실패 문서는 비어 있다")
  void reportJobTerminalFailureIsFailedEverywhere() {
    // Given: OCR은 통과했지만 리포트 생성이 재전달 한도를 다 써 초안 없이 저널에만 남았다
    UUID reportId = saveReport(false);
    insertReportJobFailure(reportId, true);

    // When
    ReportAnalysisStatusResponse status = reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);
    ReportCardListResponse.Card card = cardsByReportId().get(reportId);
    CustomerReportDetailResponse detail = reportQueryService.getReportDetail(ownerId, "USER", reportId);

    // Then: 리포트 단위 실패라 특정 문서가 없고, error_type은 응답 어디에도 실리지 않는다(필드 자체가 없음)
    assertThat(status.analysisState()).isEqualTo("FAILED");
    assertThat(status.failureReason()).isEqualTo("UNKNOWN");
    assertThat(status.failureMessage()).isEqualTo("처리에 실패했어요 — 확인 중입니다");
    assertThat(status.reuploadGuidance()).isEqualTo("HOLD");
    assertThat(status.failedAt()).isEqualTo(FAILED_AT);
    assertThat(status.failedDocuments()).isNotNull().isEmpty();
    assertThat(card.analysisState()).isEqualTo("FAILED");
    assertThat(card.analysisFailureReason()).isEqualTo("UNKNOWN");
    assertThat(detail.analysisState()).isEqualTo("FAILED");
    assertThat(detail.analysisFailureMessage()).isEqualTo("처리에 실패했어요 — 확인 중입니다");
  }

  @Test
  @DisplayName("리포트 생성 저널의 재전달 중(terminal=false) 행은 무시되고, terminal 행이 지워지면 FAILED도 바로 풀린다")
  void reportJobNonTerminalIsIgnoredAndRecoveryIsImmediate() {
    // Given
    UUID retrying = saveReport(false);
    UUID recovered = saveReport(false);
    insertReportJobFailure(retrying, false);
    insertReportJobFailure(recovered, true);
    assertThat(reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, recovered).analysisState())
        .isEqualTo("FAILED");

    // When: 같은 리포트의 다른 메시지가 성공해 워커가 terminal 행을 지웠다(회복)
    jdbcTemplate.update("DELETE FROM ai.report_job_failures WHERE report_id = ?", recovered);

    // Then: FAILED를 저장하지 않으므로 다음 조회에서 바로 돌아온다
    Map<UUID, ReportAnalysis> analyses = reportAnalysisResolver.resolveAll(List.of(retrying, recovered));
    assertThat(analyses.get(retrying).state().name()).isEqualTo("PROCESSING");
    assertThat(analyses.get(recovered).state().name()).isEqualTo("PROCESSING");
  }

  @Test
  @DisplayName("① — LLM 거절로 BLOCKED가 된 리포트는 리포트 생성 저널 행이 남아 있어도 BLOCKED다")
  void blockedBeatsLingeringReportJobFailure() {
    // Given: 경합 등으로 저널 행이 남은 상태에서 워커가 거절을 BLOCKED로 기록했다
    UUID reportId = saveReport(false);
    insertReportJobFailure(reportId, true);
    markStatus(reportId, "BLOCKED");

    // When
    ReportAnalysisStatusResponse status = reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then: 사용자 문구는 입력 가드레일 차단과 같다
    assertThat(status.analysisState()).isEqualTo("BLOCKED");
    assertThat(status.failureReason()).isNull();
    assertThat(status.failureMessage()).isEqualTo("처리할 수 없는 요청이에요. 고객센터로 문의해주세요.");
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
        reportAnalysisResolver.resolveAll(Arrays.asList(failed, clean, failed, null));

    // Then: 중복·null은 걸러지고 요청한 리포트만 담긴다
    assertThat(analyses).containsOnlyKeys(failed, clean);
    assertThat(analyses.get(failed).isFailed()).isTrue();
    assertThat(analyses.get(clean).state().name()).isEqualTo("PROCESSING");
  }

  @Test
  @DisplayName("resolveAll — 개별 문서 품질 게이트(ai.ocr_results)로 걸린 NEEDS_REUPLOAD는 REQUIRES_NEW 격리를 넘어 문서를 특정한다")
  void resolveAllExposesQualityGateDocumentAcrossRequiresNewBoundary() {
    // Given: PR #65/#66 — OCR은 성공했지만 신뢰도 낮음+도메인 정보 미검출로 ocr_quality='needs_reupload'.
    // NeedsReuploadDocumentReader가 REQUIRES_NEW(별도 커넥션)라 실제 커밋 경로로만 이 조회가 검증된다.
    UUID reportId = saveReport(false);
    UUID attachmentId = insertNeedsReuploadDocument(reportId);
    markStatus(reportId, "NEEDS_REUPLOAD");

    // When
    Map<UUID, ReportAnalysis> analyses = reportAnalysisResolver.resolveAll(List.of(reportId));

    // Then
    ReportAnalysis analysis = analyses.get(reportId);
    assertThat(analysis.state().name()).isEqualTo("NEEDS_REUPLOAD");
    assertThat(analysis.documents()).singleElement().satisfies(document -> {
      assertThat(document.attachmentId()).isEqualTo(attachmentId);
      assertThat(document.reason()).isNull();
    });
  }

  @Test
  @DisplayName("#401 — NEEDS_REUPLOAD가 아닌 리포트만 있으면 목록·상세·폴링 어디서도 재업로드 입력을 조회하지 않고 응답은 그대로다")
  void nonReuploadReportsNeverQueryReuploadInputs() {
    // Given: PROCESSING·FAILED·COMPLETED·BLOCKED, 그중 BLOCKED에는 조회되면 안 되는 off_domain 행까지 둔다
    UUID processing = saveReport(false);
    UUID failed = saveReport(false);
    UUID completed = saveReport(true);
    UUID blocked = saveReport(false);
    insertFailure(failed, "unreadable_file", true);
    markStatus(blocked, "BLOCKED");
    insertReadiness(blocked, "off_domain");

    // When
    Map<UUID, ReportCardListResponse.Card> cards = cardsByReportId();
    CustomerReportDetailResponse detail = reportQueryService.getReportDetail(ownerId, "USER", blocked);
    List<ReportAnalysisStatusResponse> polls = List.of(processing, failed, completed, blocked).stream()
        .map(reportId -> reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId))
        .toList();

    // Then: 두 ai 입력은 한 번도 조회되지 않고(커넥션 획득·dev의 실패 쿼리 제거), 사유·상태는 지금과 같다
    verify(claimReadinessViewRepository, never()).findAllByReportIdIn(anyCollection());
    verify(ocrResultViewRepository, never()).findAllByReportIdInAndOcrQuality(anyCollection(), anyString());
    assertThat(cards.get(failed).analysisFailureReason()).isEqualTo("UNREADABLE_FILE");
    assertThat(cards.get(blocked).analysisState()).isEqualTo("BLOCKED");
    assertThat(cards.get(blocked).analysisFailureReason()).isNull();
    assertThat(cards.get(completed).analysisFailureReason()).isNull();
    assertThat(cards.get(processing).analysisFailureReason()).isNull();
    assertThat(detail.analysisState()).isEqualTo("BLOCKED");
    assertThat(detail.analysisFailureReason()).isNull();
    assertThat(polls).extracting(ReportAnalysisStatusResponse::analysisState)
        .containsExactly("PROCESSING", "FAILED", "COMPLETED", "BLOCKED");
    assertThat(polls).extracting(ReportAnalysisStatusResponse::failureReason)
        .containsExactly(null, "UNREADABLE_FILE", null, null);
  }

  @Test
  @DisplayName("#401 — NEEDS_REUPLOAD 1건이 섞인 페이지는 두 재업로드 입력을 각 1회, 그 리포트 id 하나로만 조회한다")
  @SuppressWarnings("unchecked")
  void mixedPageQueriesReuploadInputsOnlyForNeedsReupload() {
    // Given
    UUID processing = saveReport(false);
    UUID needsReupload = saveReport(false);
    markStatus(needsReupload, "NEEDS_REUPLOAD");
    insertReadiness(needsReupload, "off_domain");

    // When
    Map<UUID, ReportCardListResponse.Card> cards = cardsByReportId();

    // Then
    ArgumentCaptor<Collection<UUID>> readinessIds = ArgumentCaptor.forClass(Collection.class);
    ArgumentCaptor<Collection<UUID>> documentIds = ArgumentCaptor.forClass(Collection.class);
    verify(claimReadinessViewRepository, times(1)).findAllByReportIdIn(readinessIds.capture());
    verify(ocrResultViewRepository, times(1))
        .findAllByReportIdInAndOcrQuality(documentIds.capture(), eq("needs_reupload"));
    assertThat(readinessIds.getValue()).containsExactly(needsReupload);
    assertThat(documentIds.getValue()).containsExactly(needsReupload);
    assertThat(cards.get(needsReupload).analysisFailureReason()).isEqualTo("DOMAIN_MISMATCH");
    assertThat(cards.get(processing).analysisFailureReason()).isNull();
  }
}
