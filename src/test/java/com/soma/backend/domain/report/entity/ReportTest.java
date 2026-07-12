package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** Report Aggregate 상태 전이(design.md §2.5) 단위 테스트. */
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
  @DisplayName("AWAITING_ADOPTION → AWAITING_ADOPTION(재수정) 및 → COUNSELING 전이는 허용된다")
  void adoptionSelfAndCounseling() {
    Report reedit = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    reedit.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    assertThat(reedit.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);

    Report counseling = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    counseling.applyReviewTransition(ReportStatus.COUNSELING);
    assertThat(counseling.getStatus()).isEqualTo(ReportStatus.COUNSELING);
  }

  @Test
  @DisplayName("COUNSELING → CLOSED 전이는 허용된다")
  void counselingToClosed() {
    Report report = reportWithStatus(ReportStatus.COUNSELING);

    report.applyReviewTransition(ReportStatus.CLOSED);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.CLOSED);
  }

  @Test
  @DisplayName("COUNSELING → COUNSELING(no-op)은 항상 허용된다")
  void counselingSelfNoop() {
    Report report = reportWithStatus(ReportStatus.COUNSELING);

    report.applyReviewTransition(ReportStatus.COUNSELING);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.COUNSELING);
  }

  @Test
  @DisplayName("AWAITING_INSPECTION에서 COUNSELING으로 건너뛰면 INVALID_STATUS_TRANSITION")
  void inspectionToCounselingRejected() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);

    assertThatThrownBy(() -> report.applyReviewTransition(ReportStatus.COUNSELING))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("CLOSED는 종료 상태이므로 다른 target으로 전이 불가하나 자기 자신(no-op)은 허용된다")
  void closedIsTerminalExceptSelf() {
    Report report = reportWithStatus(ReportStatus.CLOSED);

    assertThatThrownBy(() -> report.applyReviewTransition(ReportStatus.COUNSELING))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

    report.applyReviewTransition(ReportStatus.CLOSED);
    assertThat(report.getStatus()).isEqualTo(ReportStatus.CLOSED);
  }

  @Test
  @DisplayName("사정사는 임의로 CLOSED로 건너뛸 수 없다(상태머신 우선, design.md §2.5)")
  void adjusterCannotSkipToClosed() {
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);

    assertThatThrownBy(() -> report.applyReviewTransition(ReportStatus.CLOSED))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("applyReviewStart: AWAITING_INSPECTION은 착수로 AWAITING_ADOPTION으로 파생 전이된다")
  void reviewStartFromInspection() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);

    report.applyReviewStart();

    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
  }

  @Test
  @DisplayName("applyReviewStart: 이미 AWAITING_ADOPTION이면 상태를 그대로 유지한다")
  void reviewStartKeepsAdoption() {
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);

    report.applyReviewStart();

    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
  }

  @Test
  @DisplayName("applyReviewStart: COUNSELING·CLOSED는 검수 대상이 아니므로 INVALID_STATUS_TRANSITION")
  void reviewStartRejectsNonReviewable() {
    Report counseling = reportWithStatus(ReportStatus.COUNSELING);
    assertThatThrownBy(counseling::applyReviewStart)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

    Report closed = reportWithStatus(ReportStatus.CLOSED);
    assertThatThrownBy(closed::applyReviewStart)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }
}
