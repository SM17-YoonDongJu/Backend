package com.soma.backend.report.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** Report Aggregate 상태 전이(design.md §4) 단위 테스트. */
class ReportTest {

  private static Report reportWithStatus(ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  @Test
  @DisplayName("AWAITING_INSPECTION → AWAITING_ADOPTION 전이는 허용된다")
  void inspectionToAdoption() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);

    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
  }

  @Test
  @DisplayName("AWAITING_ADOPTION → AWAITING_ADOPTION(재수정) 및 → MATCHED 전이는 허용된다")
  void adoptionSelfAndMatched() {
    Report reedit = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    reedit.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    assertThat(reedit.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);

    Report matched = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    matched.applyReviewTransition(ReportStatus.MATCHED);
    assertThat(matched.getStatus()).isEqualTo(ReportStatus.MATCHED);
  }

  @Test
  @DisplayName("COUNSELING → MATCHED 전이는 허용된다")
  void counselingToMatched() {
    Report report = reportWithStatus(ReportStatus.COUNSELING);

    report.applyReviewTransition(ReportStatus.MATCHED);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.MATCHED);
  }

  @Test
  @DisplayName("AWAITING_INSPECTION에서 MATCHED로 건너뛰면 INVALID_STATUS_TRANSITION")
  void inspectionToMatchedRejected() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);

    assertThatThrownBy(() -> report.applyReviewTransition(ReportStatus.MATCHED))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("MATCHED는 종료 상태이므로 어떤 target으로도 전이 불가")
  void matchedIsTerminal() {
    Report report = reportWithStatus(ReportStatus.MATCHED);

    assertThatThrownBy(() -> report.applyReviewTransition(ReportStatus.MATCHED))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    assertThat(report.getStatus()).isEqualTo(ReportStatus.MATCHED);
  }

  @Test
  @DisplayName("사정사는 임의로 COUNSELING으로 전이할 수 없다(상태머신 우선, design.md §4 주석)")
  void adjusterCannotSetCounseling() {
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);

    assertThatThrownBy(() -> report.applyReviewTransition(ReportStatus.COUNSELING))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }
}
