package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.dto.ReportDetailResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.PendingReviewRow;
import com.soma.backend.domain.report.repository.ReportHoldRepository;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#1(요약)·API#2(목록) 조회 유스케이스 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class PendingReviewQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportHoldRepository reportHoldRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;

  @InjectMocks
  private PendingReviewQueryService service;

  @Test
  @DisplayName("summary는 pending/due-soon/in-progress 카운트를 반환하고 due-soon 기준은 now - 5일(SLA 7 - 여유 2)")
  void summaryUsesSlaThreshold() {
    UUID adjusterId = UUID.randomUUID();
    given(reportRepository.countPending()).willReturn(9L);
    given(reportRepository.countDueSoon(any(LocalDateTime.class))).willReturn(3L);
    given(reportReviewRepository.countInProgressByAdjusterId(adjusterId)).willReturn(4L);

    LocalDateTime before = LocalDateTime.now().minusDays(5);
    PendingReviewSummaryResponse summary = service.getSummary(adjusterId);
    LocalDateTime after = LocalDateTime.now().minusDays(5);

    assertThat(summary.pendingCount()).isEqualTo(9L);
    assertThat(summary.dueSoonCount()).isEqualTo(3L);
    assertThat(summary.inProgressCount()).isEqualTo(4L);

    ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reportRepository).countDueSoon(captor.capture());
    assertThat(captor.getValue()).isBetween(before, after);
  }

  @Test
  @DisplayName("목록 아이템의 offer_headroom은 claimed_max - offered(음수 허용), null은 0 처리")
  void listMapsDerivedFields() {
    UUID reportId = UUID.randomUUID();
    PendingReviewRow row = row(reportId, 1000L, 5000L, 8000L, 2L, true);
    Pageable pageable = PageRequest.of(0, 20);
    Page<PendingReviewRow> page = new PageImpl<>(List.of(row), pageable, 1);
    given(reportRepository.findPendingReviewRows(null, null, null, reportId, pageable))
        .willReturn(page);

    PendingReviewListResponse result =
        service.getPendingReviewList(null, null, null, reportId, pageable);

    PendingReviewListResponse.Item item = result.items().get(0);
    assertThat(item.offerHeadroom()).isEqualTo(-3000L);
    assertThat(item.issueCount()).isEqualTo(2L);
    assertThat(item.held()).isTrue();
    assertThat(item.createdAt()).isEqualTo(LocalDateTime.of(2026, 5, 31, 9, 0));
    assertThat(result.totalElements()).isEqualTo(1L);
  }

  @Test
  @DisplayName("null 파생값(issueCount/held)은 0/false로 안전 처리")
  void listNullSafe() {
    UUID adjusterId = UUID.randomUUID();
    PendingReviewRow row = row(UUID.randomUUID(), null, null, null, null, null);
    Pageable pageable = PageRequest.of(0, 20);
    given(reportRepository.findPendingReviewRows(null, null, null, adjusterId, pageable))
        .willReturn(new PageImpl<>(List.of(row), pageable, 1));

    PendingReviewListResponse.Item item =
        service.getPendingReviewList(null, null, null, adjusterId, pageable).items().get(0);

    assertThat(item.offerHeadroom()).isZero();
    assertThat(item.issueCount()).isZero();
    assertThat(item.held()).isFalse();
  }

  @Test
  @DisplayName("잘못된 status 필터 값은 조용한 빈 결과 대신 VALIDATION_ERROR(400)로 거른다")
  void invalidStatusRejected() {
    Pageable pageable = PageRequest.of(0, 20);
    assertThatThrownBy(
        () -> service.getPendingReviewList("NOT_A_STATUS", null, null, UUID.randomUUID(), pageable))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("잘못된 accidentType 필터 값(구 한글 값 등)은 VALIDATION_ERROR(400)로 거른다")
  void invalidAccidentTypeRejected() {
    Pageable pageable = PageRequest.of(0, 20);
    assertThatThrownBy(
        () -> service.getPendingReviewList(null, "질병", null, UUID.randomUUID(), pageable))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("상세 조회(API#6): report·쟁점·첨부·region/held 조립, accident_type은 DB 값(getValue)으로 내린다")
  void getReportDetailAssembles() {
    UUID reportId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();

    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "caseNo", "20260531-042");
    ReflectionTestUtils.setField(report, "title", "우측 슬관절 후방십자인대 파열");
    ReflectionTestUtils.setField(report, "accidentType", AccidentType.DISABILITY);
    ReflectionTestUtils.setField(report, "status", ReportStatus.AWAITING_INSPECTION);
    ReflectionTestUtils.setField(report, "claimedMinAmount", 12_000_000L);
    ReflectionTestUtils.setField(report, "claimedMaxAmount", 18_000_000L);
    ReflectionTestUtils.setField(report, "offeredAmount", 8_500_000L);
    ReflectionTestUtils.setField(report, "confidenceLevel", "high");
    ReflectionTestUtils.setField(report, "isMasked", true);
    ReflectionTestUtils.setField(report, "documents", Map.of("진단서.pdf", "s3://bucket/reports/진단서.pdf"));

    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(issue, "title", "장해등급 과소 산정 가능");
    ReflectionTestUtils.setField(issue, "tags", List.of("약관 제12조", "진단서"));

    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportRepository.findRegionByReportId(reportId)).willReturn("서울 강남");
    given(reportHoldRepository.existsByReportIdAndAdjusterId(reportId, adjusterId)).willReturn(true);
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issue));

    ReportDetailResponse result = service.getReportDetail(reportId, adjusterId);

    assertThat(result.reportId()).isEqualTo(reportId);
    assertThat(result.accidentType()).isEqualTo("disability");
    assertThat(result.region()).isEqualTo("서울 강남");
    assertThat(result.isMasked()).isTrue();
    assertThat(result.offerHeadroom()).isEqualTo(9_500_000L);
    assertThat(result.issues()).hasSize(1);
    assertThat(result.issues().get(0).tags()).containsExactly("약관 제12조", "진단서");
    assertThat(result.issueCount()).isEqualTo(1);
    assertThat(result.documents()).hasSize(1);
    assertThat(result.documents().get(0).name()).isEqualTo("진단서.pdf");
    assertThat(result.documents().get(0).url()).isEqualTo("s3://bucket/reports/진단서.pdf");
    assertThat(result.documentCount()).isEqualTo(1);
    assertThat(result.held()).isTrue();
  }

  @Test
  @DisplayName("상세 조회(API#6): 존재하지 않는 report면 REPORT_NOT_FOUND(404)")
  void getReportDetailNotFound() {
    UUID reportId = UUID.randomUUID();
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getReportDetail(reportId, UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  private static PendingReviewRow row(
      UUID reportId, Long claimedMin, Long claimedMax, Long offered, Long issueCount, Boolean held) {
    return new PendingReviewRow() {
      @Override
      public UUID getReportId() {
        return reportId;
      }

      @Override
      public String getCaseNo() {
        return "CASE-1";
      }

      @Override
      public String getTitle() {
        return "제목";
      }

      @Override
      public String getAccidentType() {
        return "질병";
      }

      @Override
      public String getRegion() {
        return "서울";
      }

      @Override
      public String getStatus() {
        return "AWAITING_INSPECTION";
      }

      @Override
      public Long getClaimedMinAmount() {
        return claimedMin;
      }

      @Override
      public Long getClaimedMaxAmount() {
        return claimedMax;
      }

      @Override
      public Long getOfferedAmount() {
        return offered;
      }

      @Override
      public Long getIssueCount() {
        return issueCount;
      }

      @Override
      public Boolean getHeld() {
        return held;
      }

      @Override
      public LocalDateTime getCreatedAt() {
        return LocalDateTime.of(2026, 5, 31, 9, 0);
      }
    };
  }
}
