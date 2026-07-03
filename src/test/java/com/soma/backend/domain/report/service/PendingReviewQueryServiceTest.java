package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.repository.PendingReviewRow;
import com.soma.backend.domain.report.repository.ReportRepository;

/** API#1(요약)·API#2(목록) 조회 유스케이스 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class PendingReviewQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;

  @InjectMocks
  private PendingReviewQueryService service;

  @Test
  @DisplayName("summary는 pending/due-soon 카운트를 반환하고 due-soon 기준은 now - 5일(SLA 7 - 여유 2)")
  void summaryUsesSlaThreshold() {
    given(reportRepository.countPending()).willReturn(9L);
    given(reportRepository.countDueSoon(any(LocalDateTime.class))).willReturn(3L);

    LocalDateTime before = LocalDateTime.now().minusDays(5);
    PendingReviewSummaryResponse summary = service.getSummary();
    LocalDateTime after = LocalDateTime.now().minusDays(5);

    assertThat(summary.pendingCount()).isEqualTo(9L);
    assertThat(summary.dueSoonCount()).isEqualTo(3L);

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
    };
  }
}
