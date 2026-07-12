package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.dto.AdjusterHomeResponse;
import com.soma.backend.domain.report.repository.AdjusterIdentityRow;
import com.soma.backend.domain.report.repository.InProgressCaseRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * 사정사 홈 집계 유스케이스 단위 테스트. 누적 검수·상담·평점은 adjuster_profiles 비정규화에서 읽고,
 * 이번 달 완료만 report_reviews 실시간 집계임을 검증한다(+ limit clamp·단계 파생).
 */
@ExtendWith(MockitoExtension.class)
class AdjusterHomeQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;

  @InjectMocks
  private AdjusterHomeQueryService service;

  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    adjusterId = UUID.randomUUID();
  }

  @Test
  @DisplayName("집계 조립: 대기 풀·신규·진행중은 실시간, 이번 달은 실시간, 누적·상담·평점은 adjuster_profiles")
  void assemblesHome() {
    given(reportRepository.countPendingPool()).willReturn(5L);
    given(reportRepository.countPendingPoolNew(any())).willReturn(2L);
    given(reportRepository.findAdjusterIdentity(adjusterId))
        .willReturn(identity("김도현", "https://cdn/a.png", 240, 9, new java.math.BigDecimal("4.9"), 86));
    given(reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(eq(adjusterId), any(), any()))
        .willReturn(14L);
    given(reportReviewRepository.countInProgressByAdjusterId(adjusterId)).willReturn(2L);
    given(reportReviewRepository.findInProgressCases(eq(adjusterId), any(Pageable.class)))
        .willReturn(List.of(inProgressRow("AWAITING_ADOPTION", "SENT")));

    AdjusterHomeResponse result = service.getHome(adjusterId, 5);

    assertThat(result.adjuster().id()).isEqualTo(adjusterId);
    assertThat(result.adjuster().name()).isEqualTo("김도현");
    assertThat(result.adjuster().avatarUrl()).isEqualTo("https://cdn/a.png");

    AdjusterHomeResponse.Summary summary = result.summary();
    assertThat(summary.pendingCount()).isEqualTo(5L);
    assertThat(summary.pendingNewCount()).isEqualTo(2L);
    assertThat(summary.inProgressCount()).isEqualTo(2L);
    assertThat(summary.monthlyCompletedCount()).isEqualTo(14L);
    assertThat(summary.totalCompletedCount()).isEqualTo(240L);
    assertThat(summary.consultationConvertedCount()).isEqualTo(9L);
    assertThat(summary.rating().average()).isEqualTo(4.9);
    assertThat(summary.rating().reviewCount()).isEqualTo(86L);

    assertThat(result.inProgressCases().total()).isEqualTo(2L);
    assertThat(result.inProgressCases().items()).hasSize(1);
    AdjusterHomeResponse.InProgressCases.Item item = result.inProgressCases().items().get(0);
    assertThat(item.stageLabel()).isEqualTo("고객 검토 대기");
    assertThat(item.progressPercent()).isEqualTo(70);
  }

  @Test
  @DisplayName("비정규화 컬럼이 아직 null이면 누적·상담 0, 평점 average null·후기 0으로 안전 처리")
  void denormalizedNullSafe() {
    stubMinimal();
    given(reportReviewRepository.findInProgressCases(eq(adjusterId), any(Pageable.class)))
        .willReturn(List.of());

    AdjusterHomeResponse.Summary summary = service.getHome(adjusterId, 5).summary();

    assertThat(summary.totalCompletedCount()).isZero();
    assertThat(summary.consultationConvertedCount()).isZero();
    assertThat(summary.rating().average()).isNull();
    assertThat(summary.rating().reviewCount()).isZero();
  }

  @Test
  @DisplayName("신규 판정 threshold는 now - 24h 근방으로 전달된다")
  void newThresholdIsPast24h() {
    stubMinimal();
    given(reportReviewRepository.findInProgressCases(eq(adjusterId), any(Pageable.class)))
        .willReturn(List.of());
    java.time.LocalDateTime before = java.time.LocalDateTime.now().minusHours(24);
    service.getHome(adjusterId, 5);
    java.time.LocalDateTime after = java.time.LocalDateTime.now().minusHours(24);

    ArgumentCaptor<java.time.LocalDateTime> captor = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
    verify(reportRepository).countPendingPoolNew(captor.capture());
    assertThat(captor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }

  @Test
  @DisplayName("in_progress_limit는 [1,20]로 clamp: 0 이하는 기본 5, 20 초과는 20")
  void clampsLimit() {
    stubMinimal();
    given(reportReviewRepository.findInProgressCases(eq(adjusterId), any(Pageable.class)))
        .willReturn(List.of());
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    service.getHome(adjusterId, 0);
    service.getHome(adjusterId, 999);

    verify(reportReviewRepository, org.mockito.Mockito.times(2))
        .findInProgressCases(eq(adjusterId), captor.capture());
    assertThat(captor.getAllValues().get(0).getPageSize()).isEqualTo(5);
    assertThat(captor.getAllValues().get(1).getPageSize()).isEqualTo(20);
  }

  @Test
  @DisplayName("COUNSELING 검수는 상담 중·진행률 90으로 파생된다")
  void counselingStage() {
    stubMinimal();
    given(reportReviewRepository.findInProgressCases(eq(adjusterId), any(Pageable.class)))
        .willReturn(List.of(inProgressRow("COUNSELING", "COUNSELING")));

    AdjusterHomeResponse.InProgressCases.Item item =
        service.getHome(adjusterId, 5).inProgressCases().items().get(0);

    assertThat(item.stageLabel()).isEqualTo("상담 중");
    assertThat(item.progressPercent()).isEqualTo(90);
  }

  private void stubMinimal() {
    given(reportRepository.countPendingPool()).willReturn(0L);
    given(reportRepository.countPendingPoolNew(any())).willReturn(0L);
    given(reportRepository.findAdjusterIdentity(adjusterId))
        .willReturn(identity("이름", null, null, null, null, null));
    given(reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(eq(adjusterId), any(), any()))
        .willReturn(0L);
    given(reportReviewRepository.countInProgressByAdjusterId(adjusterId)).willReturn(0L);
  }

  private static AdjusterIdentityRow identity(
      String name, String avatarUrl, Integer casesReviewed, Integer completedConsultCount,
      java.math.BigDecimal ratingMean, Integer reviewCount) {
    return new AdjusterIdentityRow() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getAvatarUrl() {
        return avatarUrl;
      }

      @Override
      public Integer getCasesReviewed() {
        return casesReviewed;
      }

      @Override
      public Integer getCompletedConsultCount() {
        return completedConsultCount;
      }

      @Override
      public java.math.BigDecimal getRatingMean() {
        return ratingMean;
      }

      @Override
      public Integer getReviewCount() {
        return reviewCount;
      }
    };
  }

  private static InProgressCaseRow inProgressRow(String reportStatus, String reviewStatus) {
    UUID reportId = UUID.randomUUID();
    return new InProgressCaseRow() {
      @Override
      public UUID getReportId() {
        return reportId;
      }

      @Override
      public String getCaseNo() {
        return "20260528-022";
      }

      @Override
      public String getAccidentType() {
        return "disability";
      }

      @Override
      public String getTitle() {
        return "장해등급 재산정 의견 작성 중";
      }

      @Override
      public String getReportStatus() {
        return reportStatus;
      }

      @Override
      public String getReviewStatus() {
        return reviewStatus;
      }
    };
  }
}
