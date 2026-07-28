package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.dto.CreateReportRequest;
import com.soma.backend.domain.report.dto.CreateReportResponse;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.kafka.OcrJob;
import com.soma.backend.infra.kafka.OcrJobOutboxPort;

/** ReportCommandService 유스케이스 검증 — 생성(아웃박스 발행 포함) + 제안 채택/거절. */
@ExtendWith(MockitoExtension.class)
class ReportCommandServiceTest {

  @Mock
  private UserClaimRepository userClaimRepository;
  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportAttachmentRepository reportAttachmentRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private OcrJobOutboxPort ocrJobOutboxPort;
  @InjectMocks
  private ReportCommandService service;

  private final UUID userId = UUID.randomUUID();
  private final UUID reportId = UUID.randomUUID();
  private final UUID proposalId = UUID.randomUUID();
  private final UUID adjusterId = UUID.randomUUID();

  private static <T> T withId(T entity) {
    ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    return entity;
  }

  @Test
  void createReport_persistsClaimReportAttachments_andEnqueuesOcrJobPerDocument() {
    given(reportRepository.nextCaseNoSequence(any())).willReturn(1);
    given(userClaimRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));
    given(reportRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));
    given(reportAttachmentRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));

    CreateReportRequest.Document pdf =
        new CreateReportRequest.Document("https://bucket.s3.ap-northeast-2.amazonaws.com/uploads/a.pdf",
            "진단서.pdf", "진단서", ".pdf");
    CreateReportRequest.Document img =
        new CreateReportRequest.Document("https://bucket.s3.ap-northeast-2.amazonaws.com/uploads/b.jpg",
            "사진.jpg", "기타", ".jpg");
    CreateReportRequest request = new CreateReportRequest(
        UUID.randomUUID(), AccidentType.MEDICAL_INDEMNITY, LocalDate.now(), List.of("급성 충수염"),
        1_420_000L, List.of(), "사고 경위", null, List.of(pdf, img), "질문");

    CreateReportResponse response = service.createReport(userId, request);

    assertThat(response.status()).isEqualTo(ReportStatus.AWAITING_INSPECTION);
    assertThat(response.reportId()).isNotNull();
    verify(reportAttachmentRepository, times(2)).save(any());

    ArgumentCaptor<OcrJob> jobCaptor = ArgumentCaptor.forClass(OcrJob.class);
    verify(ocrJobOutboxPort, times(2)).enqueue(jobCaptor.capture());
    OcrJob published = jobCaptor.getValue();
    assertThat(published.claimId()).isNotNull();
    assertThat(published.reportId()).isNotNull();
    assertThat(published.attachmentId()).isNotNull();

    List<OcrJob> jobs = jobCaptor.getAllValues();
    assertThat(jobs).extracting(OcrJob::docIndex).containsExactly(1, 2);
    assertThat(jobs).extracting(OcrJob::docTotal).containsExactly(2, 2);
  }

  @Test
  void createReport_succeeds_whenProductIdNull() {
    given(reportRepository.nextCaseNoSequence(any())).willReturn(1);
    given(userClaimRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));
    given(reportRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));

    CreateReportRequest request = new CreateReportRequest(
        null, AccidentType.MEDICAL_INDEMNITY, LocalDate.now(), List.of("급성 충수염"), null, List.of(),
        null, null, List.of(), null);

    CreateReportResponse response = service.createReport(userId, request);

    assertThat(response.status()).isEqualTo(ReportStatus.AWAITING_INSPECTION);
    assertThat(response.reportId()).isNotNull();
    verify(ocrJobOutboxPort, never()).enqueue(any());
  }

  @Test
  void createReport_throwsMissingRequiredField_whenAccidentTypeNull() {
    CreateReportRequest request = new CreateReportRequest(
        UUID.randomUUID(), null, LocalDate.now(), List.of(), null, List.of(),
        null, null, List.of(), null);

    assertThatThrownBy(() -> service.createReport(userId, request))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    verify(ocrJobOutboxPort, never()).enqueue(any());
  }

  @Test
  void decide_accepted_closesReportAndAcceptsReview() {
    given(reportRepository.findById(reportId)).willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.COUNSELING)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "ACCEPTED");

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(response.adjusterId()).isEqualTo(adjusterId);
  }

  @Test
  void decide_rejected_keepsReportStatus() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "REJECTED");

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
  }

  @Test
  void decide_forbidden_whenNotOwner() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(UUID.randomUUID(), ReportStatus.COUNSELING)));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }

  @Test
  void decide_proposalNotFound_whenReviewMissing() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.COUNSELING)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROPOSAL_NOT_FOUND));
  }

  @Test
  void decide_validationError_whenStatusInvalid() {
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "MAYBE"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @Test
  void decide_invalidStateTransition_whenReportNotCounseling() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
  }

  @Test
  void decide_invalidStateTransition_whenProposalAlreadyDecided() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.COUNSELING)));
    ReportReview decided = review();
    ReflectionTestUtils.setField(decided, "status", ReviewStatus.ACCEPTED);
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(decided));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "REJECTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
  }

  private Report reportOwnedBy(UUID ownerId, ReportStatus status) {
    Report report = Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260709-001");
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  private ReportReview review() {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "id", proposalId);
    return review;
  }
}
