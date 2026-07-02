package com.soma.backend.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.report.application.dto.ReviewReportCommand;
import com.soma.backend.report.application.dto.ReviewReportResult;
import com.soma.backend.report.domain.model.Report;
import com.soma.backend.report.domain.model.ReportIssue;
import com.soma.backend.report.domain.model.ReportReview;
import com.soma.backend.report.domain.model.ReportStatus;
import com.soma.backend.report.domain.repository.ReportIssueRepository;
import com.soma.backend.report.domain.repository.ReportRepository;
import com.soma.backend.report.domain.repository.ReportReviewIssueRepository;
import com.soma.backend.report.domain.repository.ReportReviewRepository;

/** API#4 검수 반영 유스케이스 단위 테스트(§10 경계 케이스). */
@ExtendWith(MockitoExtension.class)
class ReportReviewCommandServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;
  @Mock
  private ReportReviewIssueRepository reportReviewIssueRepository;

  @InjectMocks
  private ReportReviewCommandService service;

  private UUID reportId;
  private UUID adjusterId;

  @BeforeEach
  void setUp() {
    reportId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
  }

  private static Report reportWithStatus(ReportStatus status) {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "status", status);
    ReflectionTestUtils.setField(report, "id", UUID.randomUUID());
    return report;
  }

  private ReportReview persistedReview() {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "id", UUID.randomUUID());
    return review;
  }

  private ReportIssue issueWithId(UUID issueId) {
    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "id", issueId);
    return issue;
  }

  private ReviewReportCommand command(String status, List<ReviewReportCommand.IssueReviewCommand> issues) {
    return new ReviewReportCommand(adjusterId, reportId, List.of(), List.of(), issues, "피드백", status);
  }

  @Test
  @DisplayName("존재하지 않는 report면 REPORT_NOT_FOUND(404)")
  void reportNotFound() {
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.review(command("AWAITING_ADOPTION", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  @Test
  @DisplayName("status 미기재면 MISSING_REQUIRED_FIELD(400)")
  void statusBlank() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));

    assertThatThrownBy(() -> service.review(command("  ", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }

  @Test
  @DisplayName("파싱 불가능한 status면 VALIDATION_ERROR(400)")
  void statusInvalid() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));

    assertThatThrownBy(() -> service.review(command("UNKNOWN", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  @DisplayName("issue_id가 해당 report 소속이 아니면 REPORT_ISSUE_NOT_FOUND(404)")
  void issueNotBelongingToReport() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());

    ReviewReportCommand.IssueReviewCommand issue = new ReviewReportCommand.IssueReviewCommand(
        UUID.randomUUID(), "ACCEPTED", "의견", null, null);

    assertThatThrownBy(() -> service.review(command("AWAITING_ADOPTION", List.of(issue))))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_ISSUE_NOT_FOUND);
  }

  @Test
  @DisplayName("review_status=MODIFIED인데 modified_reason 없으면 MISSING_REQUIRED_FIELD(400)")
  void modifiedReasonMissing() {
    UUID issueId = UUID.randomUUID();
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issueWithId(issueId)));

    ReviewReportCommand.IssueReviewCommand issue = new ReviewReportCommand.IssueReviewCommand(
        issueId, "MODIFIED", "의견", null, null);

    assertThatThrownBy(() -> service.review(command("AWAITING_ADOPTION", List.of(issue))))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }

  @Test
  @DisplayName("review_status=EXCLUDED인데 excluded_reason 없으면 MISSING_REQUIRED_FIELD(400)")
  void excludedReasonMissing() {
    UUID issueId = UUID.randomUUID();
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issueWithId(issueId)));

    ReviewReportCommand.IssueReviewCommand issue = new ReviewReportCommand.IssueReviewCommand(
        issueId, "EXCLUDED", "의견", null, null);

    assertThatThrownBy(() -> service.review(command("AWAITING_ADOPTION", List.of(issue))))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }

  @Test
  @DisplayName("MATCHED(종료) report에 재검수 target 지정 시 INVALID_STATUS_TRANSITION(400)")
  void invalidTransitionOnMatched() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.MATCHED)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.empty());
    given(reportReviewRepository.save(any(ReportReview.class))).willAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(() -> service.review(command("MATCHED", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("본인 REPORT_REVIEWS 행이 없으면 upsert 생성 후 상태 전이 및 결과 반환")
  void upsertNewReviewAndTransition() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);
    UUID issueId = UUID.randomUUID();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issueWithId(issueId)));
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.empty());
    given(reportReviewRepository.save(any(ReportReview.class))).willReturn(persistedReview());
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportCommand.IssueReviewCommand issue = new ReviewReportCommand.IssueReviewCommand(
        issueId, "ACCEPTED", "의견", null, null);

    ReviewReportResult result = service.review(command("AWAITING_ADOPTION", List.of(issue)));

    assertThat(result.status()).isEqualTo(ReportStatus.AWAITING_ADOPTION.name());
    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    verify(reportReviewIssueRepository).deleteAllByReportReviewId(any(UUID.class));
    verify(reportReviewIssueRepository).saveAll(any());
    verify(reportRepository).save(report);
  }
}
