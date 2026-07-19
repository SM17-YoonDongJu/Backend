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
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * API#4 검수 반영 유스케이스 단위 테스트(§10 경계 케이스).
 * status는 클라가 지정하지 않고 서버가 현재 REPORTS.status에서 파생한다(applyReviewStart).
 * 반영은 부분(partial) — 보낸 필드·쟁점만 upsert하고 안 보낸 것은 유지한다.
 */
@ExtendWith(MockitoExtension.class)
class ReportReviewCommandServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;
  @Mock
  private ReportReviewSkeletonInitializer reportReviewSkeletonInitializer;

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

  private ReportReviewIssue reviewIssueRow(UUID reportIssueId, IssueReviewStatus status, String opinion) {
    return new ReportReviewIssue(reportIssueId, null, null, null, status, opinion, null, null);
  }

  private ReviewReportRequest request(List<ReviewReportRequest.IssueReview> issues) {
    return new ReviewReportRequest(1_000_000L, 3_000_000L, List.of(), List.of(), List.of(), issues, "피드백");
  }

  @Test
  @DisplayName("존재하지 않는 report면 REPORT_NOT_FOUND(404)")
  void reportNotFound() {
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  @Test
  @DisplayName("issue_id가 해당 report 소속이 아니면 REPORT_ISSUE_NOT_FOUND(404)")
  void issueNotBelongingToReport() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.AWAITING_INSPECTION)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        null, UUID.randomUUID(), "ACCEPTED", null, null, null, "의견", null, null);

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of(issue))))
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
        null, null, "ACCEPTED", null, null, null, "의견", null, null);

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of(issue))))
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
        null, issueId, "MODIFIED", null, null, null, "의견", null, null);

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of(issue))))
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
        null, issueId, "EXCLUDED", null, null, null, "의견", null, null);

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of(issue))))
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
        null, null, "ADDED", null, null, null, "의견", null, null);

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of(issue))))
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
        null, null, "ADDED", "신규 쟁점 제목", "신규 쟁점 설명", 1_500_000L, null, null, null);

    ReviewReportResponse result = service.review(adjusterId, reportId, request(List.of(issue)));

    assertThat(result.status()).isEqualTo(ReportStatus.AWAITING_ADOPTION.name());
    assertThat(review.getIssues()).hasSize(1);
    assertThat(review.getIssues().get(0).getTitle()).isEqualTo("신규 쟁점 제목");
    assertThat(review.getIssues().get(0).getImpactAmount()).isEqualTo(1_500_000L);
    verify(reportReviewSkeletonInitializer).ensureExists(reportId, adjusterId);
    verify(reportReviewRepository).save(review);
  }

  @Test
  @DisplayName("CLOSED(종료) report는 검수 대상이 아니므로 INVALID_STATE_TRANSITION(400)")
  void invalidTransitionOnClosed() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.CLOSED)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("COUNSELING(상담) report도 검수 대상이 아니므로 INVALID_STATE_TRANSITION(400)")
  void invalidTransitionOnCounseling() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportWithStatus(ReportStatus.COUNSELING)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());

    assertThatThrownBy(() -> service.review(adjusterId, reportId, request(List.of())))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("AWAITING_INSPECTION이면 착수로 AWAITING_ADOPTION 전이 + 쟁점 upsert·REPORTS 저장")
  void inspectionStartsAndTransitions() {
    Report report = reportWithStatus(ReportStatus.AWAITING_INSPECTION);
    ReportReview review = persistedReview();
    UUID issueId = UUID.randomUUID();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issueWithId(issueId)));
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(review));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportRequest.IssueReview issue = new ReviewReportRequest.IssueReview(
        null, issueId, "ACCEPTED", null, null, null, "의견", null, null);

    ReviewReportResponse result = service.review(adjusterId, reportId, request(List.of(issue)));

    assertThat(result.status()).isEqualTo(ReportStatus.AWAITING_ADOPTION.name());
    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(review.getIssues()).hasSize(1);
    verify(reportReviewSkeletonInitializer).ensureExists(reportId, adjusterId);
    verify(reportReviewRepository).save(review);
    verify(reportRepository).save(report);
  }

  @Test
  @DisplayName("이미 AWAITING_ADOPTION이면 재반영 시 상태를 그대로 유지한다")
  void adoptionKeepsStatus() {
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    ReportReview review = persistedReview();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(review));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportResponse result = service.review(adjusterId, reportId, request(List.of()));

    assertThat(result.status()).isEqualTo(ReportStatus.AWAITING_ADOPTION.name());
    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    verify(reportRepository).save(report);
  }

  @Test
  @DisplayName("부분 반영: null 필드는 기존 검수 내용을 유지하고 보낸 필드만 갱신한다")
  void partialContentKeepsUnsentFields() {
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    ReportReview review = persistedReview();
    ReflectionTestUtils.setField(review, "estimateMinAmount", 100L);
    ReflectionTestUtils.setField(review, "review", "기존 의견");
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of());
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(review));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportRequest partial = new ReviewReportRequest(null, 500L, null, null, null, null, null);
    service.review(adjusterId, reportId, partial);

    assertThat(review.getEstimateMinAmount()).isEqualTo(100L);
    assertThat(review.getEstimateMaxAmount()).isEqualTo(500L);
    assertThat(review.getReview()).isEqualTo("기존 의견");
  }

  @Test
  @DisplayName("부분 반영: 보낸 쟁점만 upsert되고 안 보낸 쟁점은 유지된다")
  void partialIssueUpsertKeepsOthers() {
    UUID issueId1 = UUID.randomUUID();
    UUID issueId2 = UUID.randomUUID();
    Report report = reportWithStatus(ReportStatus.AWAITING_ADOPTION);
    ReportReview review = persistedReview();
    review.getIssues().add(reviewIssueRow(issueId1, IssueReviewStatus.ACCEPTED, "기존1"));
    review.getIssues().add(reviewIssueRow(issueId2, IssueReviewStatus.ACCEPTED, "기존2"));
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId))
        .willReturn(List.of(issueWithId(issueId1), issueWithId(issueId2)));
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId))
        .willReturn(Optional.of(review));
    given(reportRepository.save(any(Report.class))).willAnswer(inv -> inv.getArgument(0));

    ReviewReportRequest.IssueReview only1 = new ReviewReportRequest.IssueReview(
        null, issueId1, "EXCLUDED", null, null, null, "제외 의견", null, "제외 사유");
    service.review(adjusterId, reportId, request(List.of(only1)));

    assertThat(review.getIssues()).hasSize(2);
    ReportReviewIssue updated = review.getIssues().stream()
        .filter(it -> issueId1.equals(it.getReportIssueId())).findFirst().orElseThrow();
    assertThat(updated.getReviewStatus()).isEqualTo(IssueReviewStatus.EXCLUDED);
    ReportReviewIssue kept = review.getIssues().stream()
        .filter(it -> issueId2.equals(it.getReportIssueId())).findFirst().orElseThrow();
    assertThat(kept.getReviewStatus()).isEqualTo(IssueReviewStatus.ACCEPTED);
  }
}
