package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.soma.backend.domain.report.entity.AnalysisState;
import com.soma.backend.domain.report.entity.ClaimReadinessView;
import com.soma.backend.domain.report.entity.ClaimReadinessViewFixture;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReuploadCause;
import com.soma.backend.domain.report.entity.ReuploadReason;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * {@link ReportAnalysisResolver}의 degrade 분기 단위 테스트.
 *
 * <p>목록·상세는 어떤 조회 실패든 낮추지만, 전용 폴링은 "테이블이 아직 없음"(SQLState 42P01)일 때만 낮추고 나머지는
 * 던져야 한다 — 012가 적용된 뒤의 진짜 장애를 PROCESSING으로 가리지 않기 위해서다. 실제 PostgreSQL 예외 체인에서
 * SQLState가 살아 있는지는 {@code ReportJobJournalDegradeIntegrationTest}(테이블 DROP)가 확인한다.
 *
 * <p>#401 재업로드 원인({@code ai.claim_readiness})은 폴링을 포함해 어떤 실패든 낮추고 원인별로 센다(design.md §6). 품질 미달
 * 문서·재업로드 원인은 NEEDS_REUPLOAD 리포트에만 조회한다. 로그는 이 테스트의 reportId·고유 문자열로 줄을 골라 확인한다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("ReportAnalysisResolver degrade 단위 테스트")
class ReportAnalysisResolverTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private TerminalFailureJournalReader terminalFailureJournalReader;
  @Mock
  private NeedsReuploadDocumentReader needsReuploadDocumentReader;
  @Mock
  private ClaimReadinessReader claimReadinessReader;

  private SimpleMeterRegistry meterRegistry;
  private ReportAnalysisResolver resolver;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    resolver = new ReportAnalysisResolver(reportRepository, terminalFailureJournalReader,
        needsReuploadDocumentReader, claimReadinessReader, meterRegistry);
  }

  private static InvalidDataAccessResourceUsageException sqlFailure(String sqlState) {
    return new InvalidDataAccessResourceUsageException("journal read failed", new SQLException("boom", sqlState));
  }

  private double degraded(String journal) {
    return meterRegistry.get("analysis.journal.degraded").tag("journal", journal).counter().count();
  }

  private double reuploadDegraded(String cause) {
    return meterRegistry.get("analysis.reupload_reason.degraded").tag("cause", cause).counter().count();
  }

  private static Report report(UUID reportId, ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  /** 이 테스트의 고유 문자열(reportId 등)이 찍힌 WARN 줄. 레벨 패턴과 무관하게 "WARN" 토큰이 같은 줄에 있다. */
  private static List<String> warnLines(CapturedOutput output, String marker) {
    return output.getAll().lines()
        .filter(line -> line.contains(marker))
        .filter(line -> line.contains("WARN"))
        .toList();
  }

  @Test
  @DisplayName("폴링 — 리포트 생성 저널 테이블이 아직 없으면(42P01) 빈 결과로 낮추고 degrade를 센다")
  void pollingDegradesWhenJournalTableIsMissing() {
    UUID reportId = UUID.randomUUID();
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList())).willThrow(sqlFailure("42P01"));

    assertThat(resolver.reportJobFailuresOrEmptyIfJournalMissing(List.of(reportId))).isEmpty();
    assertThat(degraded("report_job")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("폴링 — 테이블이 있는데 못 읽으면(컬럼 변경 42703 등) 삼키지 않고 던진다(거짓 PROCESSING 금지)")
  void pollingRethrowsWhenJournalExistsButCannotBeRead() {
    UUID reportId = UUID.randomUUID();
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList())).willThrow(sqlFailure("42703"));

    assertThatThrownBy(() -> resolver.reportJobFailuresOrEmptyIfJournalMissing(List.of(reportId)))
        .isInstanceOf(InvalidDataAccessResourceUsageException.class);
    assertThat(degraded("report_job")).isZero();
  }

  @Test
  @DisplayName("목록·상세 — 리포트 생성 저널이 어떤 이유로 실패해도 판정은 이어지고 degrade만 센다")
  void resolveAllDegradesOnAnyReportJobJournalFailure() {
    // Given: 컬럼 변경처럼 테이블이 있는데 못 읽는 실패라도 목록·상세는 가용성이 우선이다
    UUID reportId = UUID.randomUUID();
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList())).willThrow(sqlFailure("42703"));
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(report));

    // When
    Map<UUID, ReportAnalysis> analyses = resolver.resolveAll(List.of(reportId));

    // Then
    assertThat(analyses.get(reportId).state()).isEqualTo(AnalysisState.PROCESSING);
    assertThat(degraded("report_job")).isEqualTo(1.0);
    assertThat(degraded("ocr_job")).isZero();
  }

  @Test
  @DisplayName("목록·상세 — 리더의 REQUIRES_NEW 시작(커넥션 획득)이 실패해도 500 없이 판정이 이어진다")
  void resolveAllDegradesWhenJournalReaderCannotStartTransaction() {
    // Given: 두 저널 리더 모두 커넥션을 못 얻는다(TransactionException — DataAccessException이 아니다)
    UUID reportId = UUID.randomUUID();
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    CannotCreateTransactionException noConnection =
        new CannotCreateTransactionException("Could not open JPA EntityManager for transaction");
    given(terminalFailureJournalReader.findTerminalOcrJobFailures(anyList())).willThrow(noConnection);
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList())).willThrow(noConnection);
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(report));

    // When
    Map<UUID, ReportAnalysis> analyses = resolver.resolveAll(List.of(reportId));

    // Then
    assertThat(analyses.get(reportId).state()).isEqualTo(AnalysisState.PROCESSING);
    assertThat(degraded("ocr_job")).isEqualTo(1.0);
    assertThat(degraded("report_job")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("폴링 — 커넥션을 못 얻으면(TransactionException) 삼키지 않고 던진다(테이블 부재만 degrade)")
  void pollingRethrowsTransactionException() {
    UUID reportId = UUID.randomUUID();
    given(terminalFailureJournalReader.findTerminalReportJobFailures(anyList()))
        .willThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

    assertThatThrownBy(() -> resolver.reportJobFailuresOrEmptyIfJournalMissing(List.of(reportId)))
        .isInstanceOf(CannotCreateTransactionException.class);
    assertThat(degraded("report_job")).isZero();
  }

  @Test
  @DisplayName("목록·상세 — OCR 저널 degrade는 journal=ocr_job으로 따로 센다")
  void resolveAllCountsOcrJournalDegradeSeparately() {
    UUID reportId = UUID.randomUUID();
    given(terminalFailureJournalReader.findTerminalOcrJobFailures(anyList())).willThrow(sqlFailure("42501"));
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of());

    resolver.resolveAll(List.of(reportId));

    assertThat(degraded("ocr_job")).isEqualTo(1.0);
    assertThat(degraded("report_job")).isZero();
  }

  // ---- #401 재업로드 원인(ai.claim_readiness) ----

  @ParameterizedTest(name = "sqlState={0}")
  @ValueSource(strings = {"42P01", "42703"})
  @DisplayName("#401 — 테이블·컬럼이 아직 없으면(010·013 미적용) 빈 결과로 낮추고 schema_missing만 센다(WARN 없음)")
  void reuploadCausesDegradeQuietlyWhenSchemaIsMissing(String sqlState, CapturedOutput output) {
    UUID reportId = UUID.randomUUID();
    given(claimReadinessReader.findByReportIds(anyList())).willThrow(sqlFailure(sqlState));

    assertThat(resolver.reuploadCausesOrEmpty(List.of(reportId))).isEmpty();
    assertThat(reuploadDegraded("schema_missing")).isEqualTo(1.0);
    assertThat(reuploadDegraded("query_failed")).isZero();
    assertThat(warnLines(output, "ai.claim_readiness")).isEmpty();
  }

  @Test
  @DisplayName("#401 — 권한 없음(42501)은 query_failed이고 WARN에 SQLState·클래스 이름만 남는다(예외 메시지 없음)")
  void reuploadCausesDegradeOnPermissionDeniedWithWarn(CapturedOutput output) {
    UUID reportId = UUID.randomUUID();
    given(claimReadinessReader.findByReportIds(anyList())).willThrow(sqlFailure("42501"));

    assertThat(resolver.reuploadCausesOrEmpty(List.of(reportId))).isEmpty();
    assertThat(reuploadDegraded("query_failed")).isEqualTo(1.0);
    assertThat(reuploadDegraded("schema_missing")).isZero();
    assertThat(warnLines(output, "ai.claim_readiness")).singleElement().asString()
        .contains("42501")
        .contains("InvalidDataAccessResourceUsageException")
        .contains("SQLException");
    assertThat(output.getAll()).doesNotContain("journal read failed").doesNotContain("boom");
  }

  @Test
  @DisplayName("#401 — 리더 트랜잭션 시작(커넥션 획득) 실패도 500 없이 빈 결과 + query_failed다")
  void reuploadCausesDegradeWhenReaderCannotStartTransaction() {
    given(claimReadinessReader.findByReportIds(anyList()))
        .willThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

    assertThat(resolver.reuploadCausesOrEmpty(List.of(UUID.randomUUID()))).isEmpty();
    assertThat(reuploadDegraded("query_failed")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("#401 — 풀 고갈의 실제 예외 체인(원인이 SQLState 없는 SQLException)도 NPE 없이 빈 결과 + query_failed다")
  void reuploadCausesDegradeWhenPoolTimesOutWithoutSqlState() {
    // Given: Hikari 풀 타임아웃은 SQLState가 null인 SQLTransientConnectionException을 원인으로 싣는다
    //        (위 테스트처럼 원인 없는 예외로는 isSchemaMissing의 SQLState 검사 경로를 타지 않는다)
    given(claimReadinessReader.findByReportIds(anyList())).willThrow(new CannotCreateTransactionException(
        "Could not open JPA EntityManager for transaction",
        new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out")));

    // When & Then
    assertThat(resolver.reuploadCausesOrEmpty(List.of(UUID.randomUUID()))).isEmpty();
    assertThat(reuploadDegraded("query_failed")).isEqualTo(1.0);
    assertThat(reuploadDegraded("schema_missing")).isZero();
  }

  @Test
  @DisplayName("#401 — 리포트당 행이 2개 이상이면(계약 위반) 그 리포트만 빼고 ambiguous_rows를 센다(claim_id는 로그에 없음)")
  void reuploadCausesSkipAmbiguousReports(CapturedOutput output) {
    UUID ambiguousReportId = UUID.randomUUID();
    UUID clearReportId = UUID.randomUUID();
    ClaimReadinessView first = ClaimReadinessViewFixture.of(ambiguousReportId, "off_domain", List.of());
    ClaimReadinessView second = ClaimReadinessViewFixture.of(ambiguousReportId, "missing_required", List.of("policy"));
    given(claimReadinessReader.findByReportIds(List.of(ambiguousReportId, clearReportId))).willReturn(Map.of(
        ambiguousReportId, List.of(first, second),
        clearReportId, List.of(ClaimReadinessViewFixture.of(clearReportId, "off_domain", List.of()))));

    Map<UUID, ReuploadCause> causes = resolver.reuploadCausesOrEmpty(List.of(ambiguousReportId, clearReportId));

    assertThat(causes).containsOnlyKeys(clearReportId);
    assertThat(causes.get(clearReportId).reason()).isEqualTo(ReuploadReason.DOMAIN_MISMATCH);
    assertThat(reuploadDegraded("ambiguous_rows")).isEqualTo(1.0);
    assertThat(warnLines(output, ambiguousReportId.toString())).singleElement().asString().contains("rows=2");
    assertThat(output.getAll())
        .doesNotContain(String.valueOf(ReflectionTestUtils.getField(first, "claimId")))
        .doesNotContain(String.valueOf(ReflectionTestUtils.getField(second, "claimId")));
  }

  @Test
  @DisplayName("#401 — 사유가 일반 안내 값(unreadable·NULL)이거나 행이 없으면 맵에서 빠진다")
  void reuploadCausesOmitGeneralGuidance() {
    UUID unreadable = UUID.randomUUID();
    UUID nullReason = UUID.randomUUID();
    UUID noRow = UUID.randomUUID();
    given(claimReadinessReader.findByReportIds(anyList())).willReturn(Map.of(
        unreadable, List.of(ClaimReadinessViewFixture.of(unreadable, "unreadable", List.of())),
        nullReason, List.of(ClaimReadinessViewFixture.of(nullReason, null, List.of()))));

    assertThat(resolver.reuploadCausesOrEmpty(List.of(unreadable, nullReason, noRow))).isEmpty();
    assertThat(meterRegistry.find("analysis.reupload_reason.degraded").counters())
        .allSatisfy(counter -> assertThat(counter.count()).isZero());
  }

  @Test
  @DisplayName("#401 — 대상이 비어 있으면 두 재업로드 입력 리더를 부르지 않는다")
  void reuploadInputsSkipReadersForEmptyInput() {
    assertThat(resolver.reuploadCausesOrEmpty(List.of())).isEmpty();
    assertThat(resolver.reuploadCausesOrEmpty(null)).isEmpty();
    assertThat(resolver.needsReuploadDocumentsOrEmpty(List.of())).isEmpty();
    assertThat(resolver.needsReuploadDocumentsOrEmpty(null)).isEmpty();

    verifyNoInteractions(claimReadinessReader, needsReuploadDocumentReader);
  }

  @Test
  @DisplayName("#401 하드닝 — 품질 미달 문서 리더의 트랜잭션 시작(커넥션 획득) 실패도 빈 결과로 낮춘다")
  void needsReuploadDocumentsDegradeWhenReaderCannotStartTransaction() {
    given(needsReuploadDocumentReader.findNeedsReuploadDocuments(anyList()))
        .willThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

    assertThat(resolver.needsReuploadDocumentsOrEmpty(List.of(UUID.randomUUID()))).isEmpty();
  }

  @Test
  @DisplayName("#401 — NEEDS_REUPLOAD가 없는 페이지는 품질 미달 문서·재업로드 원인을 조회하지 않는다")
  void resolveAllSkipsReuploadInputsWithoutNeedsReupload() {
    UUID processing = UUID.randomUUID();
    UUID blocked = UUID.randomUUID();
    given(reportRepository.findAllByIdIn(anyList())).willReturn(
        List.of(report(processing, ReportStatus.AWAITING_INSPECTION), report(blocked, ReportStatus.BLOCKED)));

    Map<UUID, ReportAnalysis> analyses = resolver.resolveAll(List.of(processing, blocked));

    assertThat(analyses.get(processing).state()).isEqualTo(AnalysisState.PROCESSING);
    assertThat(analyses.get(blocked).state()).isEqualTo(AnalysisState.BLOCKED);
    verifyNoInteractions(needsReuploadDocumentReader, claimReadinessReader);
  }

  @Test
  @DisplayName("#401 — 섞인 페이지에서 두 재업로드 입력 리더는 NEEDS_REUPLOAD id만 받고, 저널은 전체를 받는다")
  void resolveAllPassesOnlyNeedsReuploadIdsToReuploadReaders() {
    UUID needsReupload = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    given(reportRepository.findAllByIdIn(anyList())).willReturn(
        List.of(report(other, ReportStatus.AWAITING_INSPECTION), report(needsReupload, ReportStatus.NEEDS_REUPLOAD)));
    given(claimReadinessReader.findByReportIds(List.of(needsReupload))).willReturn(Map.of(
        needsReupload, List.of(ClaimReadinessViewFixture.of(needsReupload, "off_domain", List.of()))));

    Map<UUID, ReportAnalysis> analyses = resolver.resolveAll(List.of(other, needsReupload));

    verify(needsReuploadDocumentReader).findNeedsReuploadDocuments(List.of(needsReupload));
    verify(claimReadinessReader).findByReportIds(List.of(needsReupload));
    verify(terminalFailureJournalReader).findTerminalOcrJobFailures(List.of(other, needsReupload));
    verify(terminalFailureJournalReader).findTerminalReportJobFailures(List.of(other, needsReupload));
    assertThat(analyses.get(needsReupload).reuploadCause().reason()).isEqualTo(ReuploadReason.DOMAIN_MISMATCH);
    assertThat(analyses.get(needsReupload).failureMessage()).startsWith("업로드하신 문서에서 보험 청구 서류를");
    assertThat(analyses.get(other).reuploadCause()).isNull();
  }

  @Test
  @DisplayName("#401 — 재업로드 원인 조회가 실패해도 NEEDS_REUPLOAD 상태와 기존 일반 문구는 그대로다")
  void resolveAllKeepsNeedsReuploadWhenCauseLookupFails() {
    UUID reportId = UUID.randomUUID();
    given(reportRepository.findAllByIdIn(anyList())).willReturn(List.of(report(reportId, ReportStatus.NEEDS_REUPLOAD)));
    given(claimReadinessReader.findByReportIds(anyList())).willThrow(sqlFailure("42501"));

    ReportAnalysis analysis = resolver.resolveAll(List.of(reportId)).get(reportId);

    assertThat(analysis).isSameAs(ReportAnalysis.needsReupload());
    assertThat(analysis.failureMessage()).isEqualTo("업로드하신 문서를 알아보기 어려워요. 더 선명하게 다시 올려주세요.");
    assertThat(reuploadDegraded("query_failed")).isEqualTo(1.0);
  }
}
