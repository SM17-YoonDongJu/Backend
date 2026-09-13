package com.soma.backend.domain.adjuster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;

import com.soma.backend.domain.adjuster.entity.AdjusterProfile;
import com.soma.backend.domain.adjuster.repository.AdjusterProfileRepository;
import com.soma.backend.domain.report.repository.AdjusterRatingAggregate;
import com.soma.backend.domain.report.repository.AdjusterReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * 집계 갱신 유스케이스 단위 테스트. 잠금 조회 → 재집계 → 엔티티 반영 순서와, 프로필 행이 없을 때의
 * no-op(예외 금지)을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdjusterProfileStatsCommandService 단위 테스트")
class AdjusterProfileStatsCommandServiceTest {

  @Mock
  private AdjusterProfileRepository adjusterProfileRepository;
  @Mock
  private AdjusterReviewRepository adjusterReviewRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @InjectMocks
  private AdjusterProfileStatsCommandService service;

  private final UUID adjusterId = UUID.randomUUID();

  private AdjusterProfile profile() {
    return BeanUtils.instantiateClass(AdjusterProfile.class);
  }

  @Test
  @DisplayName("refreshRating: 재집계한 평균·건수를 프로필에 반영한다(scale 2로 저장)")
  void refreshRating_appliesAggregate() {
    AdjusterProfile profile = profile();
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.of(profile));
    given(adjusterReviewRepository.aggregateRating(adjusterId))
        .willReturn(new AdjusterRatingAggregate(13.0 / 3.0, 3L));

    service.refreshRating(adjusterId);

    assertThat(profile.getRatingMean()).isEqualByComparingTo("4.33");
    assertThat(profile.getReviewCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("refreshRating: 평가가 0건이면 rating_mean은 null, review_count는 0")
  void refreshRating_noReviews_clearsRating() {
    AdjusterProfile profile = profile();
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.of(profile));
    given(adjusterReviewRepository.aggregateRating(adjusterId))
        .willReturn(new AdjusterRatingAggregate(null, 0L));

    service.refreshRating(adjusterId);

    assertThat(profile.getRatingMean()).isNull();
    assertThat(profile.getReviewCount()).isZero();
  }

  /** 집계 쿼리가 잠금보다 먼저 돌면 동시 갱신이 옛 모수로 계산해 lost update가 난다. */
  @Test
  @DisplayName("refreshRating: 프로필 행을 잠근 뒤에 집계 쿼리를 실행한다")
  void refreshRating_locksProfileBeforeAggregating() {
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.of(profile()));
    given(adjusterReviewRepository.aggregateRating(adjusterId))
        .willReturn(new AdjusterRatingAggregate(5.0, 1L));

    service.refreshRating(adjusterId);

    InOrder order = inOrder(adjusterProfileRepository, adjusterReviewRepository);
    order.verify(adjusterProfileRepository).findByUserIdForUpdate(adjusterId);
    order.verify(adjusterReviewRepository).aggregateRating(adjusterId);
  }

  @Test
  @DisplayName("refreshRating: 프로필 행이 없으면 예외 없이 건너뛰고 집계 쿼리도 돌지 않는다")
  void refreshRating_missingProfile_isNoOp() {
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.empty());

    assertThatCode(() -> service.refreshRating(adjusterId)).doesNotThrowAnyException();

    verifyNoInteractions(adjusterReviewRepository);
  }

  @Test
  @DisplayName("refreshCompletedConsultCount: ACCEPTED 제안 수를 프로필에 반영한다")
  void refreshCompletedConsultCount_appliesAggregate() {
    AdjusterProfile profile = profile();
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.of(profile));
    given(reportReviewRepository.countAcceptedByAdjusterId(adjusterId)).willReturn(4L);

    service.refreshCompletedConsultCount(adjusterId);

    assertThat(profile.getCompletedConsultCount()).isEqualTo(4);
  }

  @Test
  @DisplayName("refreshCompletedConsultCount: 프로필 행이 없으면 예외 없이 건너뛰고 집계 쿼리도 돌지 않는다")
  void refreshCompletedConsultCount_missingProfile_isNoOp() {
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.empty());

    assertThatCode(() -> service.refreshCompletedConsultCount(adjusterId)).doesNotThrowAnyException();

    verifyNoInteractions(reportReviewRepository);
  }

  /** 집계 write는 엔티티 메서드로만 한다 — 리포지토리 save()로 우회하면 불변식(짝 맞춤·scale)이 새어 나간다. */
  @Test
  @DisplayName("갱신은 영속 엔티티의 더티 체킹에 맡기고 save()를 직접 호출하지 않는다")
  void doesNotCallSaveExplicitly() {
    AdjusterProfile profile = profile();
    given(adjusterProfileRepository.findByUserIdForUpdate(adjusterId)).willReturn(Optional.of(profile));
    given(reportReviewRepository.countAcceptedByAdjusterId(adjusterId)).willReturn(1L);

    service.refreshCompletedConsultCount(adjusterId);

    assertThat(profile.getCompletedConsultCount()).isEqualTo(1);
    verify(adjusterProfileRepository, never()).save(any(AdjusterProfile.class));
  }
}
