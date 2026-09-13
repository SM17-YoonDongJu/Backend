package com.soma.backend.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.soma.backend.domain.report.entity.AdjusterReview;

/**
 * 평점 재집계 JPQL({@code aggregateRating})이 실제 PostgreSQL에서 실행되는지 검증한다.
 *
 * <p>핵심 목적은 <b>생성자 표현식의 타입 고정</b>이다 — {@code AVG(ar.score)}의 반환 타입이
 * {@link AdjusterRatingAggregate} 생성자와 어긋나면 컴파일은 통과하고 런타임 쿼리 준비 단계에서
 * 터지므로, 실행 테스트가 아니면 잡히지 않는다. 함께 평가 0건({@code (null, 0)})과 score null 행
 * 제외(평균·건수 모수 일치)도 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdjusterReviewRepositoryExecutionTest {

  @Autowired
  private AdjusterReviewRepository adjusterReviewRepository;

  private void saveReview(UUID adjusterId, Integer score) {
    adjusterReviewRepository.save(
        AdjusterReview.create(UUID.randomUUID(), adjusterId, null, score, "후기"));
  }

  @Test
  @DisplayName("aggregateRating — 평가가 없으면 (null, 0)을 돌려준다(쿼리 자체는 실행된다)")
  void aggregateRating_noReviews_returnsNullAverageAndZeroCount() {
    AdjusterRatingAggregate aggregate = adjusterReviewRepository.aggregateRating(UUID.randomUUID());

    assertThat(aggregate).isNotNull();
    assertThat(aggregate.average()).isNull();
    assertThat(aggregate.count()).isZero();
    assertThat(aggregate.averageAsBigDecimal()).isNull();
  }

  /** AVG 반환 타입이 record 생성자(Double)와 맞는지 — 어긋나면 여기서 쿼리 준비가 실패한다. */
  @Test
  @DisplayName("aggregateRating — 평가 3건(5,4,4)의 평균·건수를 집계한다")
  void aggregateRating_averagesScores() {
    UUID adjusterId = UUID.randomUUID();
    saveReview(adjusterId, 5);
    saveReview(adjusterId, 4);
    saveReview(adjusterId, 4);

    AdjusterRatingAggregate aggregate = adjusterReviewRepository.aggregateRating(adjusterId);

    assertThat(aggregate.count()).isEqualTo(3L);
    assertThat(aggregate.average()).isNotNull();
    assertThat(aggregate.average()).isCloseTo(13.0 / 3.0, Offset.offset(1e-9));
  }

  @Test
  @DisplayName("aggregateRating — score가 null인 행은 평균·건수 양쪽에서 제외해 모수를 일치시킨다")
  void aggregateRating_excludesNullScoreRows() {
    UUID adjusterId = UUID.randomUUID();
    saveReview(adjusterId, 5);
    saveReview(adjusterId, null);

    AdjusterRatingAggregate aggregate = adjusterReviewRepository.aggregateRating(adjusterId);

    assertThat(aggregate.count()).isEqualTo(1L);
    assertThat(aggregate.average()).isEqualTo(5.0);
  }

  @Test
  @DisplayName("aggregateRating — 다른 사정사의 평가는 집계에 섞이지 않는다")
  void aggregateRating_isScopedByAdjuster() {
    UUID adjusterId = UUID.randomUUID();
    saveReview(adjusterId, 5);
    saveReview(UUID.randomUUID(), 1);

    AdjusterRatingAggregate aggregate = adjusterReviewRepository.aggregateRating(adjusterId);

    assertThat(aggregate.count()).isEqualTo(1L);
    assertThat(aggregate.average()).isEqualTo(5.0);
  }
}
