package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.report.dto.UserActivitySummaryResponse;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/** GET /users/me/activity-summary 집계 유스케이스 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class UserActivitySummaryQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;

  @InjectMocks
  private UserActivitySummaryQueryService service;

  @Test
  @DisplayName("리포트·제안·상담·종결 카운트를 각 소스에서 조회해 응답에 매핑한다")
  void mapsFourCountsFromRepositories() {
    UUID userId = UUID.randomUUID();
    given(reportRepository.countByUserId(userId)).willReturn(7L);
    given(reportReviewRepository.countProposalsByReportOwner(userId)).willReturn(12L);
    given(reportReviewRepository.countConsultsByReportOwner(userId)).willReturn(3L);
    given(reportRepository.countClosedByUserId(userId)).willReturn(2L);

    UserActivitySummaryResponse result = service.getActivitySummary(userId);

    assertThat(result.reportCount()).isEqualTo(7L);
    assertThat(result.proposalCount()).isEqualTo(12L);
    assertThat(result.consultCount()).isEqualTo(3L);
    assertThat(result.closedCount()).isEqualTo(2L);
  }
}
