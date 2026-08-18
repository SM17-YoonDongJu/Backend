package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * ReportQueryService 단위 테스트 — status 검증(valueOf), page 1-based→0-based 변환, size 클램프를 검증한다.
 * 리포지토리는 목킹하고, 서비스가 리포지토리에 넘기는 인자(status enum·Pageable)를 캡처해 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ReportQueryServiceTest {

  @InjectMocks
  private ReportQueryService reportQueryService;

  @Mock
  private ReportRepository reportRepository;

  /** 분석 상태 부착 협력자 — 목록 조립 시 호출된다(기본 스텁은 빈 맵 = 전 카드 PROCESSING). */
  @Mock
  private ReportAnalysisStatusQueryService reportAnalysisStatusQueryService;

  private void givenEmptyPage() {
    Page<ReportCardRow> empty = new PageImpl<>(List.of());
    given(reportRepository.findUserReportCards(any(), any(), any())).willReturn(empty);
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
}
