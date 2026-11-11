package com.soma.backend.domain.report.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * ProposalDecisionRequest.startsCounseling() 검증 — 상담 수락 요청 문자열 해석(파싱 로직이 서비스에서 이관됨).
 */
class ProposalDecisionRequestTest {

  @Test
  @DisplayName("ACCEPTED는 상담 수락(true)")
  void accepted() {
    assertThat(new ProposalDecisionRequest("ACCEPTED").startsCounseling()).isTrue();
  }

  @Test
  @DisplayName("REJECTED는 상담 미수락(false) — 현재 무동작")
  void rejected() {
    assertThat(new ProposalDecisionRequest("REJECTED").startsCounseling()).isFalse();
  }

  @Test
  @DisplayName("빈 값(null·공백)이면 MISSING_REQUIRED_FIELD")
  void blank() {
    assertThatThrownBy(() -> new ProposalDecisionRequest(null).startsCounseling())
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    assertThatThrownBy(() -> new ProposalDecisionRequest("   ").startsCounseling())
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("허용되지 않은 값(대소문자·다른 enum 포함)이면 VALIDATION_ERROR")
  void unknown() {
    for (String bad : new String[] {"MAYBE", "COUNSELING", "SENT", "accepted"}) {
      assertThatThrownBy(() -> new ProposalDecisionRequest(bad).startsCounseling())
          .isInstanceOfSatisfying(BusinessException.class,
              ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
  }
}
