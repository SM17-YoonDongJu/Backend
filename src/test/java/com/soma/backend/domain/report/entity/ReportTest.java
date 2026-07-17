package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

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

  @Test
  @DisplayName("accept: COUNSELING이면 담당 사정사를 확정하고 CLOSED로 종결한다")
  void acceptFromCounseling() {
    Report report = reportWithStatus(ReportStatus.COUNSELING);
    UUID adjusterId = UUID.randomUUID();

    report.accept(adjusterId);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(report.getAdjusterId()).isEqualTo(adjusterId);
  }

  @Test
  @DisplayName("accept: 아직 상담 전(COUNSELING 아님)이면 409 INVALID_STATE_TRANSITION")
  void acceptBeforeCounselingRejected() {
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);

    assertThatThrownBy(() -> report.accept(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("accept: 이미 CLOSED면 409 REPORT_ALREADY_CLOSED")
  void acceptWhenClosedRejected() {
    Report report = reportWithStatus(ReportStatus.CLOSED);

    assertThatThrownBy(() -> report.accept(UUID.randomUUID()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_ALREADY_CLOSED);
  }

  @Test
  @DisplayName("markNotSelected: AWAITING_INSPECTION·AWAITING_ADOPTION에서 NOT_SELECTED로 전이된다")
  void markNotSelectedFromPendingStates() {
    Report inspection = reportWithStatus(ReportStatus.AWAITING_INSPECTION);
    inspection.markNotSelected();
    assertThat(inspection.getStatus()).isEqualTo(ReportStatus.NOT_SELECTED);

    Report adoption = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    adoption.markNotSelected();
    assertThat(adoption.getStatus()).isEqualTo(ReportStatus.NOT_SELECTED);
  }

  @Test
  @DisplayName("markNotSelected: CLOSED·COUNSELING은 미채택 대상이 아니므로 INVALID_STATUS_TRANSITION")
  void markNotSelectedRejectsTerminalOrCounseling() {
    Report closed = reportWithStatus(ReportStatus.CLOSED);
    assertThatThrownBy(closed::markNotSelected)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

    Report counseling = reportWithStatus(ReportStatus.COUNSELING);
    assertThatThrownBy(counseling::markNotSelected)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("NOT_SELECTED 리포트는 신규 검수 대상이 아니다(applyReviewStart → INVALID_STATUS_TRANSITION)")
  void notSelectedBlocksNewReview() {
    Report report = reportWithStatus(ReportStatus.NOT_SELECTED);

    assertThatThrownBy(report::applyReviewStart)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("NOT_SELECTED는 이후 상담이 잡히면 COUNSELING으로 재개되나 CLOSED 직행·재검수(AWAITING_ADOPTION)는 불가")
  void notSelectedResumesToCounselingOnly() {
    Report resume = reportWithStatus(ReportStatus.NOT_SELECTED);
    resume.applyReviewTransition(ReportStatus.COUNSELING);
    assertThat(resume.getStatus()).isEqualTo(ReportStatus.COUNSELING);

    Report toClosed = reportWithStatus(ReportStatus.NOT_SELECTED);
    assertThatThrownBy(() -> toClosed.applyReviewTransition(ReportStatus.CLOSED))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

    Report toAdoption = reportWithStatus(ReportStatus.NOT_SELECTED);
    assertThatThrownBy(() -> toAdoption.applyReviewTransition(ReportStatus.AWAITING_ADOPTION))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }
}
