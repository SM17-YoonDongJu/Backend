package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
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

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.PendingReviewRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewStatusRow;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#1(요약)·API#2(목록) 조회 유스케이스 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class PendingReviewQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;

  @InjectMocks
  private PendingReviewQueryService service;

  @Test
  @DisplayName("summary는 pending/due-soon/in-progress 카운트를 반환하고 due-soon 기준은 now - 6일(SLA 7 - 여유 1)")
  void summaryUsesSlaThreshold() {
    UUID adjusterId = UUID.randomUUID();
    given(reportRepository.countPending()).willReturn(9L);
    given(reportRepository.countDueSoon(any(LocalDateTime.class))).willReturn(3L);
    given(reportReviewRepository.countInProgressByAdjusterId(adjusterId)).willReturn(4L);
    given(adjusterProfileRepository.findByUserId(adjusterId))
        .willReturn(Optional.of(adjusterProfile(List.of("교통사고"))));
    given(reportRepository.countPendingByAccidentTypeIn(any())).willReturn(5L);

    LocalDateTime before = LocalDateTime.now().minusDays(6);
    PendingReviewSummaryResponse summary = service.getSummary(adjusterId);
    LocalDateTime after = LocalDateTime.now().minusDays(6);

    assertThat(summary.pendingCount()).isEqualTo(9L);
    assertThat(summary.dueSoonCount()).isEqualTo(3L);
    assertThat(summary.inProgressCount()).isEqualTo(4L);
    assertThat(summary.specialtyMatchCount()).isEqualTo(5L);

    ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(reportRepository).countDueSoon(captor.capture());
    assertThat(captor.getValue()).isBetween(before, after);
  }

  private AdjusterProfile adjusterProfile(List<String> specialties) {
    AdjusterProfile profile = BeanUtils.instantiateClass(AdjusterProfile.class);
    ReflectionTestUtils.setField(profile, "specialties", specialties);
    return profile;
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
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any()))
        .willReturn(List.of(new ReportReviewStatusRow(reportId, ReviewStatus.SENT)));

    PendingReviewListResponse result =
        service.getPendingReviewList(null, null, null, reportId, pageable);

    PendingReviewListResponse.Item item = result.list().get(0);
    assertThat(item.caseId()).isEqualTo("CASE-1");
    assertThat(item.region()).isEqualTo("서울");
    assertThat(item.offerHeadroom()).isEqualTo(-3000L);
    assertThat(item.issueCount()).isEqualTo(2L);
    assertThat(item.held()).isTrue();
    assertThat(item.reportReviewStatus()).isEqualTo("SENT");
    assertThat(item.createdAt()).isEqualTo(LocalDateTime.of(2026, 5, 31, 9, 0));
    assertThat(result.pagination().totalElements()).isEqualTo(1L);
    assertThat(result.pagination().page()).isEqualTo(1);
    assertThat(result.pagination().hasNext()).isFalse();
  }

  @Test
  @DisplayName("null 파생값(issueCount/held)은 0/false로 안전 처리")
  void listNullSafe() {
    UUID adjusterId = UUID.randomUUID();
    PendingReviewRow row = row(UUID.randomUUID(), null, null, null, null, null);
    Pageable pageable = PageRequest.of(0, 20);
    given(reportRepository.findPendingReviewRows(null, null, null, adjusterId, pageable))
        .willReturn(new PageImpl<>(List.of(row), pageable, 1));
    given(reportReviewRepository.findAdjusterReviewStatuses(any(), any())).willReturn(List.of());

    PendingReviewListResponse.Item item =
        service.getPendingReviewList(null, null, null, adjusterId, pageable).list().get(0);

    assertThat(item.offerHeadroom()).isZero();
    assertThat(item.issueCount()).isZero();
    assertThat(item.held()).isFalse();
    assertThat(item.reportReviewStatus()).isNull();
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

  private static PendingReviewRow row(
      UUID reportId, Long claimedMin, Long claimedMax, Long offered, Long issueCount, Boolean held) {
    return new PendingReviewRow(
        reportId, "CASE-1", "제목", AccidentType.DISABILITY, List.of("서울"), ReportStatus.AWAITING_INSPECTION,
        claimedMin, claimedMax, offered, issueCount, held, LocalDateTime.of(2026, 5, 31, 9, 0));
  }
}
