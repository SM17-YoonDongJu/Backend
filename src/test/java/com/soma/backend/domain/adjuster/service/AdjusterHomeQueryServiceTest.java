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

/**
 * 사정사 홈 집계 유스케이스 단위 테스트. 누적 검수·상담·평점은 adjuster_profiles 비정규화에서 읽고,
 * 이번 달 완료만 report_reviews 실시간 집계임을 검증한다(+ limit clamp·단계 파생). 조회는 단일
 * AdjusterHomeRepository(QueryDSL 읽기 모델)로 위임한다.
 */
@ExtendWith(MockitoExtension.class)
class AdjusterHomeQueryServiceTest {

  @Mock
  private AdjusterHomeRepository adjusterHomeRepository;

  @InjectMocks
  private AdjusterHomeQueryService service;

  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    adjusterId = UUID.randomUUID();
  }

  @Test
  @DisplayName("집계 조립: 대기 풀·신규·진행중·이번 달은 실시간, 누적·상담·평점은 adjuster_profiles")
  void assemblesHome() {
    given(adjusterHomeRepository.countPendingPool()).willReturn(5L);
    given(adjusterHomeRepository.countPendingPoolNew(any())).willReturn(2L);
    given(adjusterHomeRepository.findAdjusterIdentity(adjusterId))
        .willReturn(new AdjusterIdentityRow("김도현", "https://cdn/a.png", 240, 9, new BigDecimal("4.9"), 86));
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
    given(adjusterHomeRepository.findInProgressCases(eq(adjusterId), anyInt())).willReturn(List.of());

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
        .willReturn(new AdjusterIdentityRow("이름", null, null, null, null, null));
    given(adjusterHomeRepository.countCompletedBetween(eq(adjusterId), any(), any())).willReturn(0L);
    given(adjusterHomeRepository.countInProgress(adjusterId)).willReturn(0L);
  }

  private static InProgressCaseRow inProgressRow(String reportStatus, String reviewStatus) {
    return new InProgressCaseRow(
        UUID.randomUUID(), "20260528-022", AccidentType.DISABILITY, "장해등급 재산정 의견 작성 중",
        ReportStatus.valueOf(reportStatus), ReviewStatus.valueOf(reviewStatus));
  }
}
