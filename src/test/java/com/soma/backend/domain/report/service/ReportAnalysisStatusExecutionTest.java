package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.soma.backend.domain.report.dto.ReportAnalysisStatusResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.repository.OcrJobFailureViewRepository;
import com.soma.backend.domain.report.repository.PendingAnalysisFailureRow;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 분석 상태 조회 유스케이스의 실제 test_db 실행 검증(design.md §15 Q1~Q7·Q10·Q11).
 *
 * <p>{@code ai.ocr_job_failures}는 AI 워커 소유라 Hibernate가 만들지 않으므로({@code @Subselect}는 DDL 대상
 * 제외) 계약 미러 스크립트로 부트스트랩한 뒤, 실패 행을 {@link JdbcTemplate}으로 직접 넣어
 * {@code @Subselect} 매핑·terminal 필터·조인 키(report_id)가 실제 SQL로 동작하는지 확인한다.
 *
 * <p>{@code @Transactional}이라 테스트 종료 시 리포트·첨부·실패 행이 모두 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/ai-contract-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("분석 상태 조회 실행 테스트")
class ReportAnalysisStatusExecutionTest {

  private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 8, 12, 14, 3, 11);

  @Autowired
  private ReportAnalysisStatusQueryService reportAnalysisStatusQueryService;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportAttachmentRepository reportAttachmentRepository;
  @Autowired
  private OcrJobFailureViewRepository ocrJobFailureViewRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;
  @PersistenceContext
  private EntityManager entityManager;

  private UUID ownerId;
  private UUID reportId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    reportId = saveReport(ownerId);
  }

  private UUID saveReport(UUID userId) {
    Report report = Report.createPending(userId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문",
        "ANL-" + UUID.randomUUID().toString().substring(0, 12));
    return reportRepository.save(report).getId();
  }

  private UUID saveAttachment(UUID targetReportId, String name) {
    ReportAttachment attachment =
        ReportAttachment.of(targetReportId, name, "https://example.test/doc", "application/pdf", "diagnosis");
    return reportAttachmentRepository.save(attachment).getId();
  }

  /** 실패 저널 행 삽입 — 내부 식별자(s3_key·user_ref·error_type)를 일부러 채워 응답 유출 여부까지 볼 수 있게 한다. */
  private void insertFailure(
      UUID targetReportId, UUID attachmentId, String failureClass, boolean terminal, LocalDateTime firstFailedAt) {
    jdbcTemplate.update("""
        INSERT INTO ai.ocr_job_failures
          (id, job_id, message_id, s3_key, user_ref, content_type, doc_type_hint, claim_id,
           report_id, attachment_id, failure_class, error_type, attempts, terminal, first_failed_at, last_failed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(), UUID.randomUUID(), "sqs-message-id", "s3://bucket/secret-key.pdf",
        "internal-user-ref", "application/pdf", "diagnosis", "claim-1",
        targetReportId, attachmentId, failureClass, "ValueError", 3, terminal,
        firstFailedAt, firstFailedAt);
  }

  private void deleteFailures(UUID targetReportId) {
    jdbcTemplate.update("DELETE FROM ai.ocr_job_failures WHERE report_id = ?", targetReportId);
  }

  @Test
  @DisplayName("Q1 — terminal=false 행만 있으면 PROCESSING이고 실패 필드는 전부 null·문서 목록은 빈 배열이다")
  void nonTerminalFailureStaysProcessing() {
    // Given: 재전달로 회복될 수 있는 일시 실패만 존재
    insertFailure(reportId, UUID.randomUUID(), "ocr_error", false, FAILED_AT);

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then
    assertThat(response.analysisState()).isEqualTo("PROCESSING");
    assertThat(response.failureReason()).isNull();
    assertThat(response.failureMessage()).isNull();
    assertThat(response.reuploadGuidance()).isNull();
    assertThat(response.failedAt()).isNull();
    assertThat(response.failedDocuments()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("Q2 — terminal=true 1건이면 FAILED + 사유·문구·재업로드 안내·첨부 파일명을 채운다")
  void terminalFailureIsReportedWithGuidance() {
    // Given
    UUID attachmentId = saveAttachment(reportId, "진단서.pdf");
    insertFailure(reportId, attachmentId, "unreadable_file", true, FAILED_AT);

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then
    assertThat(response.reportId()).isEqualTo(reportId);
    assertThat(response.analysisState()).isEqualTo("FAILED");
    assertThat(response.failureReason()).isEqualTo("UNREADABLE_FILE");
    assertThat(response.failureMessage()).isEqualTo("파일을 읽을 수 없어요. 다시 업로드해주세요");
    assertThat(response.reuploadGuidance()).isEqualTo("RECOMMENDED");
    assertThat(response.failedAt()).isEqualTo(FAILED_AT);
    assertThat(response.failedDocuments()).singleElement().satisfies(document -> {
      assertThat(document.attachmentId()).isEqualTo(attachmentId);
      assertThat(document.name()).isEqualTo("진단서.pdf");
      assertThat(document.failureReason()).isEqualTo("UNREADABLE_FILE");
    });
  }

  @Test
  @DisplayName("Q3 — AI 초안이 있으면 terminal 실패 행이 남아 있어도 COMPLETED로 내린다")
  void aiDraftWinsOverLingeringFailure() {
    // Given: 워커의 실패 행 삭제가 실패해 잔존(§8 E3)
    Report report = reportRepository.findById(reportId).orElseThrow();
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("상해후유장해"));
    reportRepository.save(report);
    insertFailure(reportId, UUID.randomUUID(), "ocr_error", true, FAILED_AT);

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then
    assertThat(response.analysisState()).isEqualTo("COMPLETED");
    assertThat(response.failureReason()).isNull();
    assertThat(response.failedDocuments()).isEmpty();
  }

  @Test
  @DisplayName("Q4 — 실패 행이 삭제되면 재조회 시 PROCESSING으로 복귀한다(상태를 저장하지 않기에 가능)")
  void deletedFailureRestoresProcessing() {
    // Given
    insertFailure(reportId, UUID.randomUUID(), "ocr_error", true, FAILED_AT);
    assertThat(reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId).analysisState())
        .isEqualTo("FAILED");

    // When: AI 워커가 회복해 저널 행을 지웠다(clear_job_failure)
    deleteFailures(reportId);

    // Then
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);
    assertThat(response.analysisState()).isEqualTo("PROCESSING");
    assertThat(response.failureReason()).isNull();
  }

  @Test
  @DisplayName("Q5(최중요) — masking_residual + unreadable_file 혼재 시 대표는 MASKING_RESIDUAL·안내는 NOT_SUPPORTED")
  void mixedReasonsNeverRecommendReupload() {
    // Given: 문서 2건이 서로 다른 사유로 확정 실패
    UUID maskedDocument = saveAttachment(reportId, "마스킹문서.pdf");
    UUID brokenDocument = saveAttachment(reportId, "깨진문서.pdf");
    insertFailure(reportId, brokenDocument, "unreadable_file", true, FAILED_AT.plusMinutes(5));
    insertFailure(reportId, maskedDocument, "masking_residual", true, FAILED_AT);

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then: 재업로드 오안내가 나가지 않는다
    assertThat(response.analysisState()).isEqualTo("FAILED");
    assertThat(response.failureReason()).isEqualTo("MASKING_RESIDUAL");
    assertThat(response.reuploadGuidance()).isEqualTo("NOT_SUPPORTED");
    assertThat(response.failureMessage()).isEqualTo("문서를 검토 중입니다");
    assertThat(response.failedAt()).isEqualTo(FAILED_AT);
    assertThat(response.failedDocuments())
        .extracting(ReportAnalysisStatusResponse.FailedDocument::failureReason)
        .containsExactlyInAnyOrder("UNREADABLE_FILE", "MASKING_RESIDUAL");
  }

  @Test
  @DisplayName("Q6 — 계약에 없는 failure_class 행도 UNKNOWN으로 폴백하고 500이 되지 않는다")
  void unknownFailureClassDoesNotFail() {
    // Given: CHECK 제약을 우회해 미지의 값을 심는다(AI팀이 계약을 먼저 확장한 상황 시뮬레이션).
    // DDL도 트랜잭션 안이라 테스트 종료 시 제약이 복구된다.
    jdbcTemplate.execute("ALTER TABLE ai.ocr_job_failures "
        + "DROP CONSTRAINT IF EXISTS ocr_job_failures_failure_class_check");
    insertFailure(reportId, UUID.randomUUID(), "brand_new_value", true, FAILED_AT);

    // When & Then
    assertThatCode(() -> reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId))
        .doesNotThrowAnyException();
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);
    assertThat(response.analysisState()).isEqualTo("FAILED");
    assertThat(response.failureReason()).isEqualTo("UNKNOWN");
    assertThat(response.reuploadGuidance()).isEqualTo("HOLD");
  }

  @Test
  @DisplayName("Q7 — attachment_id가 NULL인 실패 행도 500 없이 직렬화된다(식별자·이름만 null)")
  void nullAttachmentIdIsSerialized() {
    // Given: 역직렬화 실패·poison 훅처럼 문서 식별자가 없는 실패(§8 E9)
    insertFailure(reportId, null, "schema_invalid", true, FAILED_AT);

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then
    assertThat(response.analysisState()).isEqualTo("FAILED");
    assertThat(response.failedDocuments()).singleElement().satisfies(document -> {
      assertThat(document.attachmentId()).isNull();
      assertThat(document.name()).isNull();
      assertThat(document.failureReason()).isEqualTo("SCHEMA_INVALID");
    });
  }

  @Test
  @DisplayName("다른 리포트의 실패 행은 섞이지 않는다(조인 키 report_id 격리 — 재업로드 시 옛 실패 분리)")
  void failuresAreIsolatedByReportId() {
    // Given: 같은 사용자의 옛 리포트가 실패했고, 새 리포트를 다시 만들었다(§8 E11)
    UUID oldReportId = saveReport(ownerId);
    insertFailure(oldReportId, UUID.randomUUID(), "unreadable_file", true, FAILED_AT);

    // When
    ReportAnalysisStatusResponse newReport =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);
    ReportAnalysisStatusResponse oldReport =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, oldReportId);

    // Then
    assertThat(newReport.analysisState()).isEqualTo("PROCESSING");
    assertThat(oldReport.analysisState()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("Q10 — 타인(사정사 포함)이 조회하면 403 FORBIDDEN이다(소유자 전용)")
  void nonOwnerIsForbidden() {
    // Given: 리포트 소유자가 아닌 다른 사용자
    UUID strangerId = UUID.randomUUID();

    // When & Then
    assertThatThrownBy(() -> reportAnalysisStatusQueryService.getAnalysisStatus(strangerId, reportId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  @DisplayName("Q11 — 존재하지 않는 reportId는 404 REPORT_NOT_FOUND다")
  void missingReportIsNotFound() {
    assertThatThrownBy(() ->
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  @Test
  @DisplayName("resolveAll — 빈 입력·null 입력은 조회 없이 빈 맵이다(불필요한 IN () 쿼리 방지)")
  void resolveAllShortCircuitsOnEmptyInput() {
    assertThat(reportAnalysisStatusQueryService.resolveAll(List.of())).isEmpty();
    assertThat(reportAnalysisStatusQueryService.resolveAll(null)).isEmpty();
  }

  @Test
  @DisplayName("status='NEEDS_REUPLOAD'를 실제로 저장·조회해도 enum 매핑이 깨지지 않고 재업로드 안내가 나간다")
  void needsReuploadStatusRoundTrips() {
    // Given: AI 워커가 원시 SQL로 세팅하는 값을 같은 문자열로 저장한다(varchar(30), CHECK 없음).
    markStatus(reportId, "NEEDS_REUPLOAD");

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then
    assertThat(response.analysisState()).isEqualTo("NEEDS_REUPLOAD");
    assertThat(response.failureReason()).isNull();
    assertThat(response.failureMessage()).isEqualTo("업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.");
    assertThat(response.reuploadGuidance()).isEqualTo("RECOMMENDED");
    assertThat(response.failedAt()).isNull();
    // 개별 문서 품질 게이트로 걸린 경우(ai.ocr_results 판정)는 저널(ai.ocr_job_failures)에 흔적이 없어
    // 빈 배열이다 — 이 문서 단위 상세는 이번 스코프 밖(후속 이슈).
    assertThat(response.failedDocuments()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("청구 fan-in으로 걸린 NEEDS_REUPLOAD는 저널에 흔적이 있으면 실제 DB에서도 문서를 특정해 보여준다")
  void needsReuploadFromClaimFanInRoundTripsWithFailedDocuments() {
    // Given: PR #67 — 필수 문서 미인식·비필수 문서 결정적 실패는 개별 문서 품질 게이트와 달리 저널에 남는다.
    UUID attachmentId = saveAttachment(reportId, "보험증권.pdf");
    insertFailure(reportId, attachmentId, "unreadable_file", true, FAILED_AT);
    markStatus(reportId, "NEEDS_REUPLOAD");

    // When
    ReportAnalysisStatusResponse response =
        reportAnalysisStatusQueryService.getAnalysisStatus(ownerId, reportId);

    // Then: 상태·문구는 그대로지만 문서 목록·첨부 파일명이 실제 조인으로 채워진다.
    assertThat(response.analysisState()).isEqualTo("NEEDS_REUPLOAD");
    assertThat(response.failureReason()).isNull();
    assertThat(response.failureMessage()).isEqualTo("업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.");
    assertThat(response.failedAt()).isNull();
    assertThat(response.failedDocuments()).singleElement().satisfies(document -> {
      assertThat(document.attachmentId()).isEqualTo(attachmentId);
      assertThat(document.name()).isEqualTo("보험증권.pdf");
      assertThat(document.failureReason()).isEqualTo("UNREADABLE_FILE");
    });
  }

  @Test
  @DisplayName("종료 상태(BLOCKED·NEEDS_REUPLOAD) 리포트는 실패 통지 스윕 대상에서 제외된다(배치 슬롯 점유 방지)")
  void terminalStatusesAreExcludedFromPendingNotification() {
    // Given: 세 리포트 모두 확정 실패 행을 갖지만 두 건은 전용 스윕이 통지하는 종료 상태다.
    UUID blockedReportId = saveReport(ownerId);
    UUID needsReuploadReportId = saveReport(ownerId);
    insertFailure(reportId, UUID.randomUUID(), "ocr_error", true, FAILED_AT);
    insertFailure(blockedReportId, UUID.randomUUID(), "ocr_error", true, FAILED_AT);
    insertFailure(needsReuploadReportId, UUID.randomUUID(), "ocr_error", true, FAILED_AT);
    markStatus(blockedReportId, "BLOCKED");
    markStatus(needsReuploadReportId, "NEEDS_REUPLOAD");

    // When
    List<PendingAnalysisFailureRow> pending =
        ocrJobFailureViewRepository.findPendingNotification(FAILED_AT.minusDays(1), 100);

    // Then
    assertThat(pending).extracting(PendingAnalysisFailureRow::reportId)
        .contains(reportId)
        .doesNotContain(blockedReportId, needsReuploadReportId);
  }

  /** AI 워커의 원시 SQL 세팅을 모사한다 — 전이표를 거치지 않는 진입이라 도메인 메서드로는 만들 수 없다. */
  private void markStatus(UUID targetReportId, String status) {
    reportRepository.flush();
    jdbcTemplate.update("UPDATE reports SET status = ? WHERE id = ?", status, targetReportId);
    entityManager.clear();
  }
}
