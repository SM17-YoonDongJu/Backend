package com.soma.backend.report.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AmountRange.offerHeadroom() 파생 계산 단위 테스트(금소법 §17 범위 표현). */
class AmountRangeTest {

  @Test
  @DisplayName("offerHeadroom = claimed_max - offered")
  void headroomBasic() {
    AmountRange range = new AmountRange(1000L, 5000L, 3000L);

    assertThat(range.offerHeadroom()).isEqualTo(2000L);
  }

  @Test
  @DisplayName("offered가 claimed_max보다 크면 headroom은 음수가 될 수 있다")
  void headroomNegative() {
    AmountRange range = new AmountRange(1000L, 5000L, 8000L);

    assertThat(range.offerHeadroom()).isEqualTo(-3000L);
  }

  @Test
  @DisplayName("null 금액은 0으로 안전 처리한다")
  void headroomNullSafe() {
    assertThat(new AmountRange(null, null, null).offerHeadroom()).isZero();
    assertThat(new AmountRange(null, 5000L, null).offerHeadroom()).isEqualTo(5000L);
    assertThat(new AmountRange(null, null, 3000L).offerHeadroom()).isEqualTo(-3000L);
  }
}
