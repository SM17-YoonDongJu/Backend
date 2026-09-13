package com.soma.backend.domain.adjuster.entity;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * 평점 표시값 VO — adjuster_profiles 비정규화 컬럼(rating_mean·review_count)을 프론트 계약(항상 number)에
 * 맞춰 해석한다. 집계(후기 POST 시 갱신) 미구현이라 아직 채워지지 않았을 수 있으므로, reviewCount가
 * null·0이거나 ratingMean이 null이면 average는 0.0으로 내린다 — 평점 유무는 reviewCount로 판별한다.
 */
public record AdjusterRating(double average, int reviewCount) {

  public static AdjusterRating of(@Nullable BigDecimal ratingMean, @Nullable Integer reviewCount) {
    int count = reviewCount == null ? 0 : reviewCount;
    double average = count == 0 || ratingMean == null ? 0.0 : ratingMean.doubleValue();
    return new AdjusterRating(average, count);
  }
}
