package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.CustomerReportDetailRow;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * ReportQueryService 단위 테스트 — status 검증(valueOf), page 1-based→0-based 변환, size 클램프를 검증한다.
 * 리포지토리는 목킹하고, 서비스가 리포지토리에 넘기는 인자(status enum·Pageable)를 캡처해 확인한다.
 *
 * <p>#406 — 상세는 본 조회(Reader)가 끝난 뒤에 분석 상태를 판정하고, 리포트 재조회가 커넥션을 못 얻는 실패
 * ({@code DataAccessResourceFailureException})는 목록·상세 모두 판정만 낮춘다. 커넥션 수 자체는
 * {@code ReportQueryConnectionBudgetTest}(Hikari 풀 1개)가 실제 요청으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReportQueryServiceTest {

  @InjectMocks
  private ReportQueryService reportQueryService;

  @Mock
  private ReportRepository reportRepository;

  /** 상세 본 조회 협력자 — 리포트·인가·쟁점·상세 행·채택 의견을 readOnly 트랜잭션 하나에서 읽는다. */
  @Mock
  private CustomerReportDetailReader customerReportDetailReader;

  /** 분석 상태 부착 협력자 — 목록 조립 시 호출된다(기본 스텁은 빈 맵 = 전 카드 PROCESSING). */
  @Mock
  private ReportAnalysisResolver reportAnalysisResolver;

  private void givenEmptyPage() {
    Page<ReportCardRow> empty = new PageImpl<>(List.of());
    given(reportRepository.findUserReportCards(any(), any(), any())).willReturn(empty);
  }

  private static ReportCardRow cardRow(UUID reportId) {
    return new ReportCardRow(reportId, ReportStatus.AWAITING_INSPECTION, AccidentType.DISABILITY, null,
        LocalDateTime.of(2026, 11, 16, 9, 0), null, null, 0L, null, null, null, null);
  }

  private static CustomerReportDetailReader.Snapshot snapshot(UUID reportId, UUID ownerId) {
    Report report = Report.createPending(ownerId, null, null, AccidentType.DISABILITY, null, "질문");
    ReflectionTestUtils.setField(report, "id", reportId);
    CustomerReportDetailRow row = new CustomerReportDetailRow(null, null, null, null, null, null, null, null);
    return new CustomerReportDetailReader.Snapshot(report, List.of(), row, Map.of());
  }

  /** 풀 고갈 때 트랜잭션 없는 리포트 재조회가 실제로 던지는 예외(원인은 SQLState 없는 Hikari 타임아웃). */
  private static DataAccessResourceFailureException poolTimeout() {
    return new DataAccessResourceFailureException("Unable to acquire JDBC Connection",
        new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out"));
  }

  @Test
  @DisplayName("알 수 없는 status면 400 VALIDATION_ERROR(리포지토리 호출 없음)")
  void unknownStatusThrowsValidationError() {
    assertThatThrownBy(() -> reportQueryService.getUserReports(UUID.randomUUID(), "BOGUS", 1, 10))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    then(reportRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("유효한 status는 enum으로 파싱되고, page(3)는 0-based(2)로 변환돼 리포지토리에 전달된다")
  void validStatusParsedAndPageConverted() {
    givenEmptyPage();
    UUID userId = UUID.randomUUID();

    reportQueryService.getUserReports(userId, "COUNSELING", 3, 20);

    ArgumentCaptor<ReportStatus> statusCaptor = ArgumentCaptor.forClass(ReportStatus.class);
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    then(reportRepository).should()
        .findUserReportCards(eq(userId), statusCaptor.capture(), pageableCaptor.capture());
    assertThat(statusCaptor.getValue()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
  }

  @Test
  @DisplayName("status=NEEDS_REUPLOAD 필터도 enum 추가만으로 그대로 지원된다(parseStatus는 valueOf)")
  void needsReuploadStatusFilterIsSupported() {
    givenEmptyPage();

    reportQueryService.getUserReports(UUID.randomUUID(), "NEEDS_REUPLOAD", 1, 10);

    ArgumentCaptor<ReportStatus> statusCaptor = ArgumentCaptor.forClass(ReportStatus.class);
    then(reportRepository).should().findUserReportCards(any(), statusCaptor.capture(), any());
    assertThat(statusCaptor.getValue()).isEqualTo(ReportStatus.NEEDS_REUPLOAD);
  }

  @Test
  @DisplayName("status가 비어 있으면 필터 없이(null) 조회하고, page(0 이하)는 0으로 클램프된다")
  void blankStatusMeansNullFilterAndPageFlooredToZero() {
    givenEmptyPage();

    reportQueryService.getUserReports(UUID.randomUUID(), "  ", 0, 10);

    ArgumentCaptor<ReportStatus> statusCaptor = ArgumentCaptor.forClass(ReportStatus.class);
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    then(reportRepository).should()
        .findUserReportCards(any(), statusCaptor.capture(), pageableCaptor.capture());
    assertThat(statusCaptor.getValue()).isNull();
    assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
  }

  @Test
  @DisplayName("size 상한(100) 초과는 100으로 클램프된다")
  void oversizedPageSizeClampedToMax() {
    givenEmptyPage();

    reportQueryService.getUserReports(UUID.randomUUID(), null, 1, 500);

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    then(reportRepository).should().findUserReportCards(any(), any(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  @DisplayName("size 하한 — 0 이하는 1로 클램프된다(PageRequest.of 예외 방지)")
  void nonPositivePageSizeClampedToOne() {
    givenEmptyPage();

    reportQueryService.getUserReports(UUID.randomUUID(), null, 1, 0);

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    then(reportRepository).should().findUserReportCards(any(), any(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
  }

  @Test
  @DisplayName("#406 — 상세는 본 조회(Reader)가 끝난 뒤에 분석 상태를 판정한다(두 조회가 겹치지 않는다)")
  void detailResolvesAnalysisAfterReaderReturns() {
    // Given
    UUID ownerId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(customerReportDetailReader.read(ownerId, "USER", reportId)).willReturn(snapshot(reportId, ownerId));

    // When
    CustomerReportDetailResponse detail = reportQueryService.getReportDetail(ownerId, "USER", reportId);

    // Then
    assertThat(detail.reportId()).isEqualTo(reportId);
    InOrder inOrder = inOrder(customerReportDetailReader, reportAnalysisResolver);
    inOrder.verify(customerReportDetailReader).read(ownerId, "USER", reportId);
    inOrder.verify(reportAnalysisResolver).resolveAll(List.of(reportId));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = ErrorCode.class, names = {"REPORT_NOT_FOUND", "FORBIDDEN"})
  @DisplayName("#406 — 상세 본 조회가 404·403으로 거부되면 분석 상태를 판정하지 않고 그 예외를 그대로 낸다")
  void detailSkipsAnalysisWhenReaderRejects(ErrorCode rejection) {
    // Given
    UUID reportId = UUID.randomUUID();
    given(customerReportDetailReader.read(any(), any(), eq(reportId))).willThrow(new BusinessException(rejection));

    // When & Then
    assertThatThrownBy(() -> reportQueryService.getReportDetail(UUID.randomUUID(), "USER", reportId))
        .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(rejection));
    then(reportAnalysisResolver).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("#406 — 리포트 재조회가 커넥션을 못 얻어도 목록·받은 제안·상세는 500 없이 분석 상태를 PROCESSING으로 낮춘다")
  void listsAndDetailDegradeWhenReportReloadCannotGetConnection() {
    // Given: #406 전에는 resolveAll 자신의 트랜잭션 시작 실패(CannotCreateTransactionException)라 흡수되지 않았다
    UUID ownerId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    given(reportRepository.findUserReportCards(any(), any(), any()))
        .willReturn(new PageImpl<>(List.of(cardRow(reportId))));
    given(reportRepository.findReportsWithProposals(any(), any()))
        .willReturn(new PageImpl<>(List.of(cardRow(reportId))));
    given(customerReportDetailReader.read(ownerId, "USER", reportId)).willReturn(snapshot(reportId, ownerId));
    given(reportAnalysisResolver.resolveAll(any())).willThrow(poolTimeout());

    // When
    ReportCardListResponse reports = reportQueryService.getUserReports(ownerId, null, 1, 10);
    ReportCardListResponse proposals = reportQueryService.getReceivedProposals(ownerId, 1, 10);
    CustomerReportDetailResponse detail = reportQueryService.getReportDetail(ownerId, "USER", reportId);

    // Then
    assertThat(reports.list()).singleElement()
        .extracting(ReportCardListResponse.Card::analysisState).isEqualTo("PROCESSING");
    assertThat(proposals.list()).singleElement()
        .extracting(ReportCardListResponse.Card::analysisState).isEqualTo("PROCESSING");
    assertThat(detail.analysisState()).isEqualTo("PROCESSING");
  }
}
