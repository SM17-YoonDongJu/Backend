package com.soma.backend.domain.report.service;

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

import com.soma.backend.domain.report.dto.ReviewReportRequest;
import com.soma.backend.domain.report.dto.ReviewReportResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#4 검수 반영 유스케이스 단위 테스트(§10 경계 케이스). */
@ExtendWith(MockitoExtension.class)
class ReportReviewCommandServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;

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

  private ReviewReportRequest request(String status, List<ReviewReportRequest.IssueReview> issues) {
    return new ReviewReportRequest(1_000_000L, 3_000_000L, List.of(), List.of(), List.of(), issues, "피드백", status);
  }

  @Test
  @DisplayName("존재하지 않는 report면 REPORT_NOT_FOUND(404)")
  void reportNotFound() {
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  @Test
  @DisplayName("status 미기재면 MISSING_REQUIRED_FIELD(400)")
  void statusBlank() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request("  ", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }

  @Test
  @DisplayName("파싱 불가능한 status면 VALIDATION_ERROR(400)")
  void statusInvalid() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request("UNKNOWN", List.of())))
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

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        UUID.randomUUID(), "ACCEPTED", null, null, "의견", null, null);

    assertThatThrownBy(
        () -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue))))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_ISSUE_NOT_FOUND);
  }

  @Test
  @DisplayName("ACCEPTED/MODIFIED/EXCLUDED인데 issue_id가 없으면 REPORT_ISSUE_NOT_FOUND(404)")
  void existingStatusRequiresIssueId() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        null, "ACCEPTED", null, null, "의견", null, null);

    assertThatThrownBy(
        () -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue))))
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

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        issueId, "MODIFIED", null, null, "의견", null, null);

    assertThatThrownBy(
        () -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue))))
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

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        issueId, "EXCLUDED", null, null, "의견", null, null);

    assertThatThrownBy(
        () -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue))))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }

  @Test
  @DisplayName("review_status=ADDED인데 title/description이 없으면 MISSING_REQUIRED_FIELD(400)")
  void addedRequiresTitleAndDescription() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        null, "ADDED", null, null, "의견", null, null);

    assertThatThrownBy(
        () -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue))))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD);
  }

  @Test
  @DisplayName("review_status=ADDED이고 issue_id=null이며 title/description이 있으면 신규 쟁점으로 저장된다")
  void addedIssueWithoutIssueIdPersists() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);
    ReportReview review = persistedReview();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(review));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        null, "ADDED", "신규 쟁점 제목", "신규 쟁점 설명", null, null, null);

    ReviewReportResponse result =
        service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue)));

    assertThat(result.status()).isEqualTo(ReportStatus.AWAITING_ADOPTION.name());
    assertThat(review.getIssues()).hasSize(1);
    assertThat(review.getIssues().get(0).getTitle()).isEqualTo("신규 쟁점 제목");
    verify(reportReviewRepository).insertIfAbsent(reportId, adjusterId);
    verify(reportReviewRepository).save(review);
  }

  @Test
  @DisplayName("CLOSED(종료) report에 재검수 target 지정 시 INVALID_STATUS_TRANSITION(400)")
  void invalidTransitionOnClosed() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.CLOSED)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(persistedReview()));

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
  }

  @Test
  @DisplayName("COUNSELING → CLOSED 전이는 허용된다")
  void counselingToClosedAllowed() {
    Report report = reportWithStatus(ReportStatus.COUNSELING);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(persistedReview()));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportResponse result = service.review(adjusterId, reportId, request("CLOSED", List.of()));

    assertThat(result.status()).isEqualTo(ReportStatus.CLOSED.name());
  }

  @Test
  @DisplayName("본인 REPORT_REVIEWS 행을 멱등 생성 후 쟁점 교체·상태 전이 및 결과 반환")
  void upsertNewReviewAndTransition() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);
    ReportReview review = persistedReview();
    UUID issueId = UUID.randomUUID();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issueWithId(issueId)));
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(review));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        issueId, "ACCEPTED", null, null, "의견", null, null);

    ReviewReportResponse result =
        service.review(adjusterId, reportId, request("AWAITING_ADOPTION", List.of(issue)));

    assertThat(result.status()).isEqualTo(ReportStatus.AWAITING_ADOPTION.name());
    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(review.getIssues()).hasSize(1);
    verify(reportReviewRepository).insertIfAbsent(reportId, adjusterId);
    verify(reportReviewRepository).save(review);
    verify(reportRepository).save(report);
  }
}
