package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** ReportReview Aggregate 상태 전이(제안 채택/거절) 단위 테스트. */
class ReportReviewTest {

  private static ReportReview reviewWithStatus(ReviewStatus status) {
    ReportReview review = new ReportReview(UUID.randomUUID(), UUID.randomUUID());
    ReflectionTestUtils.setField(review, "status", status);
    return review;
  }

  @Test
  @DisplayName("검수 등록 직후 제안 상태는 SENT다")
  void newReviewStartsAsSent() {
    ReportReview review = new ReportReview(UUID.randomUUID(), UUID.randomUUID());

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.SENT);
  }

  @Test
  @DisplayName("accept: SENT 제안을 바로 채택하면 ACCEPTED로 전이된다")
  void acceptFromSent() {
    ReportReview review = reviewWithStatus(ReviewStatus.SENT);

    review.accept();

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
  }

  @Test
  @DisplayName("reject: SENT 제안을 바로 거절하면 REJECTED로 전이된다")
  void rejectFromSent() {
    ReportReview review = reviewWithStatus(ReviewStatus.SENT);

    review.reject();

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.REJECTED);
  }

  @Test
  @DisplayName("accept: 이미 채택(ACCEPTED)된 제안이면 409 INVALID_STATE_TRANSITION")
  void acceptRejectsWhenAlreadyAccepted() {
    ReportReview review = reviewWithStatus(ReviewStatus.ACCEPTED);

    assertThatThrownBy(review::accept)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("reject: 이미 거절(REJECTED)된 제안이면 409 INVALID_STATE_TRANSITION")
  void rejectRejectsWhenAlreadyRejected() {
    ReportReview review = reviewWithStatus(ReviewStatus.REJECTED);

    assertThatThrownBy(review::reject)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("isDecidable: 종료 상태(ACCEPTED/REJECTED)만 재결정 불가, SENT는 가능")
  void isDecidableReflectsTerminalState() {
    assertThat(reviewWithStatus(ReviewStatus.SENT).isDecidable()).isTrue();
    assertThat(reviewWithStatus(ReviewStatus.ACCEPTED).isDecidable()).isFalse();
    assertThat(reviewWithStatus(ReviewStatus.REJECTED).isDecidable()).isFalse();
  }
}
