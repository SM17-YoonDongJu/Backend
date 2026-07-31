package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.adjuster.dto.AdjusterHomeResponse;
import com.soma.backend.domain.adjuster.repository.AdjusterHomeRepository;
import com.soma.backend.domain.adjuster.repository.AdjusterIdentityRow;
import com.soma.backend.domain.adjuster.repository.InProgressCaseRow;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * 사정사 홈 집계 유스케이스 단위 테스트. 상담·평점은 adjuster_profiles 비정규화에서 읽고, 완료(누적·이번 달)는
 * report_reviews 실시간 집계(누적=전체 기간, 이번 달=당월)로 항상 '누적 ≥ 이번 달'임을 검증한다
 * (+ limit clamp·단계 파생). 조회는 AdjusterHomeRepository(QueryDSL)·ReportReviewRepository로 위임한다.
 */
@ExtendWith(MockitoExtension.class)
class AdjusterHomeQueryServiceTest {

  @Mock
  private AdjusterHomeRepository adjusterHomeRepository;

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
  @DisplayName("집계 조립: 대기 풀·신규·진행중·완료는 실시간(report_reviews), 상담·평점은 adjuster_profiles")
  void assemblesHome() {
    given(adjusterHomeRepository.countPendingPool()).willReturn(5L);
    given(adjusterHomeRepository.countPendingPoolNew(any())).willReturn(2L);
    given(adjusterHomeRepository.findAdjusterIdentity(adjusterId))
        .willReturn(new AdjusterIdentityRow("김도현", "https://cdn/a.png", 9, new BigDecimal("4.9"), 86));
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(240L);
    given(adjusterHomeRepository.countCompletedBetween(eq(adjusterId), any(), any())).willReturn(14L);
    given(adjusterHomeRepository.countInProgress(adjusterId)).willReturn(2L);
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt()))
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
    // 누적 완료 = report_reviews 전체 기간 카운트(240), 이번 달(14)은 그 부분집합.
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
  @DisplayName("비정규화 상담·평점이 아직 null이면 상담 0, 평점 average 0.0·후기 0으로 안전 처리")
  void denormalizedNullSafe() {
    stubMinimal();
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt())).willReturn(List.of());

    AdjusterHomeResponse.Summary summary = service.getHome(adjusterId, 5).summary();

    assertThat(summary.consultationConvertedCount()).isZero();
    assertThat(summary.rating().average()).isEqualTo(0.0);
    assertThat(summary.rating().reviewCount()).isZero();
  }

  @Test
  @DisplayName("누적 완료는 report_reviews 전체 기간 카운트라 항상 이번 달(당월 부분집합) 이상이다")
  void totalCompletedIsAllTimeReviewCount() {
    given(adjusterHomeRepository.countPendingPool()).willReturn(0L);
    given(adjusterHomeRepository.countPendingPoolNew(any())).willReturn(0L);
    given(adjusterHomeRepository.findAdjusterIdentity(adjusterId))
        .willReturn(new AdjusterIdentityRow("이름", null, null, null, null));
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(13L);
    given(adjusterHomeRepository.countCompletedBetween(eq(adjusterId), any(), any())).willReturn(10L);
    given(adjusterHomeRepository.countInProgress(adjusterId)).willReturn(0L);
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt())).willReturn(List.of());

    AdjusterHomeResponse.Summary summary = service.getHome(adjusterId, 5).summary();

    assertThat(summary.monthlyCompletedCount()).isEqualTo(10L);
    assertThat(summary.totalCompletedCount()).isEqualTo(13L);
    assertThat(summary.totalCompletedCount()).isGreaterThanOrEqualTo(summary.monthlyCompletedCount());
  }

  @Test
  @DisplayName("신규 판정 threshold는 now - 24h 근방으로 전달된다")
  void newThresholdIsPast24h() {
    stubMinimal();
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt())).willReturn(List.of());
    LocalDateTime before = LocalDateTime.now().minusHours(24);
    service.getHome(adjusterId, 5);
    LocalDateTime after = LocalDateTime.now().minusHours(24);

    ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(adjusterHomeRepository).countPendingPoolNew(captor.capture());
    assertThat(captor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
  }

  @Test
  @DisplayName("in_progress_limit는 [1,20]로 clamp: 0 이하는 기본 5, 20 초과는 20")
  void clampsLimit() {
    stubMinimal();
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt())).willReturn(List.of());
    ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);

    service.getHome(adjusterId, 0);
    service.getHome(adjusterId, 999);

    verify(adjusterHomeRepository, Mockito.times(2)).findInProgressCases(eq(adjusterId), captor.capture());
    assertThat(captor.getAllValues().get(0)).isEqualTo(5);
    assertThat(captor.getAllValues().get(1)).isEqualTo(20);
  }

  @Test
  @DisplayName("title이 null이면 기본 문구 '제목 없음'으로 내려 프론트 계약(non-null)을 보장한다")
  void titleNullSafe() {
    stubMinimal();
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt()))
        .willReturn(List.of(new InProgressCaseRow(
            UUID.randomUUID(), "20260528-023", AccidentType.DISABILITY, null,
            ReportStatus.AWAITING_INSPECTION, ReviewStatus.SENT)));

    AdjusterHomeResponse.InProgressCases.Item item =
        service.getHome(adjusterId, 5).inProgressCases().items().get(0);

    assertThat(item.title()).isEqualTo("제목 없음");
  }

  @Test
  @DisplayName("COUNSELING 검수는 상담 중·진행률 90으로 파생된다")
  void counselingStage() {
    stubMinimal();
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt()))
        .willReturn(List.of(inProgressRow("COUNSELING", "COUNSELING")));

    AdjusterHomeResponse.InProgressCases.Item item =
        service.getHome(adjusterId, 5).inProgressCases().items().get(0);

    assertThat(item.stageLabel()).isEqualTo("상담 중");
    assertThat(item.progressPercent()).isEqualTo(90);
  }

  private void stubMinimal() {
    given(adjusterHomeRepository.countPendingPool()).willReturn(0L);
    given(adjusterHomeRepository.countPendingPoolNew(any())).willReturn(0L);
    given(adjusterHomeRepository.findAdjusterIdentity(adjusterId))
        .willReturn(new AdjusterIdentityRow("이름", null, null, null, null));
    given(reportReviewRepository.countByAdjusterId(adjusterId)).willReturn(0L);
    given(adjusterHomeRepository.countCompletedBetween(eq(adjusterId), any(), any())).willReturn(0L);
    given(adjusterHomeRepository.countInProgress(adjusterId)).willReturn(0L);
  }

  private static InProgressCaseRow inProgressRow(String reportStatus, String reviewStatus) {
    return new InProgressCaseRow(
        UUID.randomUUID(), "20260528-022", AccidentType.DISABILITY, "장해등급 재산정 의견 작성 중",
        ReportStatus.valueOf(reportStatus), ReviewStatus.valueOf(reviewStatus));
  }
}
