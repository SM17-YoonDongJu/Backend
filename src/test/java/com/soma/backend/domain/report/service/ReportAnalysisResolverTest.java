package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.soma.backend.domain.report.entity.AnalysisState;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAnalysis;
import com.soma.backend.domain.report.repository.ReportRepository;

/**
 * {@link ReportAnalysisResolver}의 리포트 생성 저널 degrade 분기 단위 테스트.
 *
 * <p>목록·상세는 어떤 조회 실패든 낮추지만, 전용 폴링은 "테이블이 아직 없음"(SQLState 42P01)일 때만 낮추고 나머지는
 * 던져야 한다 — 012가 적용된 뒤의 진짜 장애를 PROCESSING으로 가리지 않기 위해서다. 실제 PostgreSQL 예외 체인에서
 * SQLState가 살아 있는지는 {@code ReportJobJournalDegradeIntegrationTest}(테이블 DROP)가 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportAnalysisResolver degrade 단위 테스트")
class ReportAnalysisResolverTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private TerminalFailureJournalReader terminalFailureJournalReader;
  @Mock
  private NeedsReuploadDocumentReader needsReuploadDocumentReader;

  private SimpleMeterRegistry meterRegistry;
  private ReportAnalysisResolver resolver;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    resolver = new ReportAnalysisResolver(
        reportRepository, terminalFailureJournalReader, needsReuploadDocumentReader, meterRegistry);
  }

  private static InvalidDataAccessResourceUsageException sqlFailure(String sqlState) {
    return new InvalidDataAccessResourceUsageException("journal read failed", new SQLException("boom", sqlState));
  }

  private double degraded(String journal) {
    return meterRegistry.get("analysis.journal.degraded").tag("journal", journal).counter().count();
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
}
