package com.soma.backend.domain.report.repository;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * 사정사 평점 재집계 결과(adjuster_profiles.rating_mean·review_count 반영용) JPQL 생성자 프로젝션.
 * 평가가 한 건도 없으면 {@code (null, 0)}이다.
 *
 * <p>{@code average}가 {@code BigDecimal}이 아니라 {@code Double}인 이유: Hibernate는 JPQL {@code AVG}의
 * 반환 타입을 Dialect와 무관하게 {@code Double}로 고정한다. 생성자 표현식은 인자 타입이 맞아야 쿼리
 * 파싱 단계에서 생성자를 찾으므로, {@code BigDecimal}로 선언하면 컴파일은 되지만 런타임에 쿼리 준비가
 * 실패한다(AdjusterReviewRepositoryExecutionTest가 이 타입을 고정한다).
 */
public record AdjusterRatingAggregate(@Nullable Double average, long count) {

  /** 저장용 평균 — 평가가 없으면 null이다(rating_mean null 계약). 반올림은 엔티티가 한다. */
  public @Nullable BigDecimal averageAsBigDecimal() {
    return average == null ? null : BigDecimal.valueOf(average);
  }
}
