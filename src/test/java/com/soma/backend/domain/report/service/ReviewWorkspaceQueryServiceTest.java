package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

import com.soma.backend.domain.report.dto.ReviewWorkspaceResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.Hospitalization;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReviewContextRow;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 검수 화면 워크스페이스 집계 조회 단위 테스트: 작업본 유무에 따른 resolved 전환과 쟁점 병합을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ReviewWorkspaceQueryServiceTest {

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private ReportAttachmentRepository reportAttachmentRepository;
  @Mock
  private UserClaimRepository userClaimRepository;

  @InjectMocks
  private ReviewWorkspaceQueryService service;

  private UUID reportId;
  private UUID adjusterId;
  private UUID claimId;

  @BeforeEach
  void setUp() {
    reportId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    claimId = UUID.randomUUID();
  }

  @Test
  @DisplayName("작업본 없음: 보장·예상보상은 AI(reports)에서 오고 started=false, 쟁점은 AI 원본만(오버레이 null)")
  void notStartedUsesAiDraft() {
    Report report = report();
    ReflectionTestUtils.setField(report, "claimId", claimId);
    ReportIssue aiIssue = issue("장해등급 과소 산정 가능");
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportRepository.findReviewContext(reportId)).willReturn(context());
    given(reportRepository.findRegionByReportId(reportId)).willReturn(List.of("서울 강남"));
    given(userClaimRepository.findById(claimId)).willReturn(Optional.of(claim()));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(aiIssue));
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId)).willReturn(Optional.empty());
    given(reportAttachmentRepository.findAllByReportId(reportId)).willReturn(List.of());

    ReviewWorkspaceResponse result = service.getReviewWorkspace(reportId, adjusterId);

    assertThat(result.started()).isFalse();
    assertThat(result.applicableGuarantees()).containsExactly("AI-보장");
    assertThat(result.aiEstimate().min()).isEqualTo(12_000_000L);
    assertThat(result.adjusterEstimate()).isNull();
    assertThat(result.review()).isNull();
    assertThat(result.reviewStatus()).isNull();
    assertThat(result.issues()).hasSize(1);
    assertThat(result.issues().get(0).aiTitle()).isEqualTo("장해등급 과소 산정 가능");
    assertThat(result.issues().get(0).reviewStatus()).isNull();
    assertThat(result.progress().total()).isEqualTo(1);
    assertThat(result.progress().accepted()).isZero();
    assertThat(result.client().nickname()).isEqualTo("운수");
    assertThat(result.claim().diagnosis()).isEqualTo("후방십자인대 파열");
    assertThat(result.claim().hospitalization()).isEqualTo("입원 10일");
  }

  @Test
  @DisplayName("작업본 있음: 보장·예상보상은 report_reviews에서, AI 쟁점에 오버레이 병합 + ADDED 포함 + progress 집계")
  void startedUsesReviewAndMergesIssues() {
    Report report = report();
    ReportIssue aiIssue = issue("장해등급 과소 산정 가능");
    UUID aiIssueId = aiIssue.getId();
    ReportReview review = review();
    review.getIssues().add(reviewIssue(aiIssueId, IssueReviewStatus.ACCEPTED, null, "인정 의견", null));
    review.getIssues().add(reviewIssue(null, IssueReviewStatus.ADDED, "외모추상 특약 누락", "신규 의견", 3_000_000L));

    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportRepository.findReviewContext(reportId)).willReturn(context());
    given(reportRepository.findRegionByReportId(reportId)).willReturn(List.of("서울 강남"));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(aiIssue));
    given(reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId)).willReturn(Optional.of(review));
    given(reportAttachmentRepository.findAllByReportId(reportId)).willReturn(List.of());

    ReviewWorkspaceResponse result = service.getReviewWorkspace(reportId, adjusterId);

    assertThat(result.started()).isTrue();
    assertThat(result.applicableGuarantees()).containsExactly("사정사-보장");
    assertThat(result.adjusterEstimate().min()).isEqualTo(14_000_000L);
    assertThat(result.reviewStatus()).isEqualTo("SENT");
    assertThat(result.issues()).hasSize(2);

    ReviewWorkspaceResponse.IssueItem merged = result.issues().get(0);
    assertThat(merged.issueId()).isEqualTo(aiIssueId);
    assertThat(merged.aiTitle()).isEqualTo("장해등급 과소 산정 가능");
    assertThat(merged.reviewStatus()).isEqualTo("ACCEPTED");
    assertThat(merged.adjusterOpinion()).isEqualTo("인정 의견");
    assertThat(merged.modifiedImpactAmount()).isNull();

    ReviewWorkspaceResponse.IssueItem added = result.issues().get(1);
    assertThat(added.issueId()).isNull();
    assertThat(added.reviewStatus()).isEqualTo("ADDED");
    assertThat(added.modifiedTitle()).isEqualTo("외모추상 특약 누락");
    assertThat(added.modifiedImpactAmount()).isEqualTo(3_000_000L);

    assertThat(result.progress().total()).isEqualTo(2);
    assertThat(result.progress().accepted()).isEqualTo(1);
  }

  @Test
  @DisplayName("존재하지 않는 report면 REPORT_NOT_FOUND")
  void reportNotFound() {
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getReviewWorkspace(reportId, adjusterId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  @Test
  @DisplayName("검수 단계가 아닌 report(CLOSED)면 REPORT_NOT_FOUND — 상세 조회와 동일한 노출 정책")
  void notInReviewPhaseReturnsNotFound() {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "status", ReportStatus.CLOSED);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));

    assertThatThrownBy(() -> service.getReviewWorkspace(reportId, adjusterId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.REPORT_NOT_FOUND);
  }

  private Report report() {
    Report report = BeanUtils.instantiateClass(Report.class);
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "status", ReportStatus.AWAITING_INSPECTION);
    ReflectionTestUtils.setField(report, "claimedMinAmount", 12_000_000L);
    ReflectionTestUtils.setField(report, "claimedMaxAmount", 18_000_000L);
    ReflectionTestUtils.setField(report, "applicableGuarantees", List.of("AI-보장"));
    return report;
  }

  private ReportIssue issue(String title) {
    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(issue, "title", title);
    return issue;
  }

  private ReportReview review() {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(review, "estimateMinAmount", 14_000_000L);
    ReflectionTestUtils.setField(review, "estimateMaxAmount", 17_500_000L);
    ReflectionTestUtils.setField(review, "applicableGuarantees", List.of("사정사-보장"));
    return review;
  }

  private ReportReviewIssue reviewIssue(
      UUID reportIssueId, IssueReviewStatus status, String title, String opinion, Long impactAmount) {
    return new ReportReviewIssue(reportIssueId, title, null, impactAmount, status, opinion, null, null);
  }

  private ReviewContextRow context() {
    return new ReviewContextRow() {
      @Override
      public String getNickname() {
        return "운수";
      }

      @Override
      public String getGender() {
        return "M";
      }

      @Override
      public LocalDate getBirthDate() {
        return LocalDate.of(1990, 3, 1);
      }

      @Override
      public LocalDateTime getJoinedAt() {
        return LocalDateTime.of(2024, 3, 1, 0, 0);
      }

      @Override
      public String getClaimAccidentType() {
        return "traffic";
      }

      @Override
      public LocalDate getAccidentDate() {
        return LocalDate.of(2026, 5, 1);
      }

      @Override
      public String getClaimDescription() {
        return "사고 경위";
      }

      @Override
      public String getAdditionalInformation() {
        return null;
      }

      @Override
      public String getProductName() {
        return "행복드림";
      }

      @Override
      public String getInsurerName() {
        return "OO손해보험";
      }
    };
  }

  private UserClaim claim() {
    UserClaim claim = BeanUtils.instantiateClass(UserClaim.class);
    ReflectionTestUtils.setField(claim, "id", claimId);
    ReflectionTestUtils.setField(claim, "details",
        ClaimDetails.of(AccidentType.TRAFFIC, List.of("후방십자인대 파열"),
            List.of(new Hospitalization(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10), "수술 및 입원"))));
    return claim;
  }
}
