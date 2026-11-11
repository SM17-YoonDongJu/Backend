package com.soma.backend.domain.adjuster.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * AdjusterProfile 집계 write 메서드의 불변식 단위 테스트.
 * "후기 0건 ⟺ 평점 null" 짝 맞춤, 음수 거부, 저장 평균 scale 2(HALF_UP) 고정을 검증한다.
 */
@DisplayName("AdjusterProfile 집계 불변식")
class AdjusterProfileTest {

  private AdjusterProfile profile() {
    return BeanUtils.instantiateClass(AdjusterProfile.class);
  }

  private void assertValidationError(ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @Nested
  @DisplayName("refreshRating")
  class RefreshRating {

    @Test
    @DisplayName("첫 평가(5점 1건)를 반영하면 rating_mean=5.00·review_count=1")
    void appliesFirstReview() {
      AdjusterProfile profile = profile();

      profile.refreshRating(new BigDecimal("5"), 1);

      assertThat(profile.getRatingMean()).isEqualByComparingTo("5.00");
      assertThat(profile.getRatingMean().scale()).isEqualTo(2);
      assertThat(profile.getReviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("무한소수 평균(13/3)은 scale 2 HALF_UP으로 반올림해 4.33으로 저장한다")
    void roundsToScaleTwo() {
      AdjusterProfile profile = profile();

      profile.refreshRating(BigDecimal.valueOf(13.0 / 3.0), 3);

      assertThat(profile.getRatingMean()).isEqualByComparingTo("4.33");
      assertThat(profile.getRatingMean().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("HALF_UP 경계(4.005)는 올림해 4.01로 저장한다")
    void roundsHalfUp() {
      AdjusterProfile profile = profile();

      profile.refreshRating(new BigDecimal("4.005"), 2);

      assertThat(profile.getRatingMean()).isEqualByComparingTo("4.01");
    }

    @Test
    @DisplayName("후기 0건이면 평균 인자가 있어도 rating_mean을 null로 강제한다(0건 ⟺ 평점 없음)")
    void forcesNullAverageWhenNoReviews() {
      AdjusterProfile profile = profile();
      profile.refreshRating(new BigDecimal("4.50"), 3);

      profile.refreshRating(new BigDecimal("4.50"), 0);

      assertThat(profile.getRatingMean()).isNull();
      assertThat(profile.getReviewCount()).isZero();
    }

    @Test
    @DisplayName("후기 0건이고 평균도 null이면 그대로 (null, 0)으로 저장한다")
    void acceptsNullAverageWhenNoReviews() {
      AdjusterProfile profile = profile();

      profile.refreshRating(null, 0);

      assertThat(profile.getRatingMean()).isNull();
      assertThat(profile.getReviewCount()).isZero();
    }

    @Test
    @DisplayName("후기가 있는데 평균이 null이면 VALIDATION_ERROR — 짝이 어긋난 값을 저장하지 않는다")
    void rejectsNullAverageWithReviews() {
      AdjusterProfile profile = profile();

      assertValidationError(() -> profile.refreshRating(null, 3));
    }

    @Test
    @DisplayName("후기 수가 음수면 VALIDATION_ERROR")
    void rejectsNegativeReviewCount() {
      AdjusterProfile profile = profile();

      assertValidationError(() -> profile.refreshRating(new BigDecimal("4.00"), -1));
    }

    @Test
    @DisplayName("같은 집계값으로 여러 번 호출해도 결과가 같다(멱등)")
    void isIdempotent() {
      AdjusterProfile profile = profile();

      profile.refreshRating(new BigDecimal("4.333"), 3);
      profile.refreshRating(new BigDecimal("4.333"), 3);

      assertThat(profile.getRatingMean()).isEqualByComparingTo("4.33");
      assertThat(profile.getReviewCount()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("refreshCompletedConsultCount")
  class RefreshCompletedConsultCount {

    @Test
    @DisplayName("재집계 건수를 그대로 덮어쓴다(증분이 아니다)")
    void overwritesWithAggregate() {
      AdjusterProfile profile = profile();
      ReflectionTestUtils.setField(profile, "completedConsultCount", 7);

      profile.refreshCompletedConsultCount(2);

      assertThat(profile.getCompletedConsultCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("0건도 정상 반영한다")
    void acceptsZero() {
      AdjusterProfile profile = profile();

      profile.refreshCompletedConsultCount(0);

      assertThat(profile.getCompletedConsultCount()).isZero();
    }

    @Test
    @DisplayName("음수면 VALIDATION_ERROR")
    void rejectsNegative() {
      AdjusterProfile profile = profile();

      assertValidationError(() -> profile.refreshCompletedConsultCount(-1));
    }
  }
}
