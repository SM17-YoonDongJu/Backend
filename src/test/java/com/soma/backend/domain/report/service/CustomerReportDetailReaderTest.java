package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.repository.CustomerReportDetailRow;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * {@link CustomerReportDetailReader} 단위 테스트 — 존재 확인(404)이 인가(403)보다 먼저이고 거부되면 이후 조회를 하지 않는지,
 * 채택 검수 의견을 맵으로 풀어 돌려주는지 확인한다. 트랜잭션 경계(본 조회가 끝난 뒤에 분석 판정)와 지연 로딩이 실제로 Reader
 * 트랜잭션 안에서 끝나는지는 {@code ReportQueryConnectionBudgetTest}가 트랜잭션 없는 상세 요청으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerReportDetailReader 단위 테스트")
class CustomerReportDetailReaderTest {

  @InjectMocks
  private CustomerReportDetailReader customerReportDetailReader;

  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportIssueRepository reportIssueRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;

  private static Report report(UUID reportId, UUID ownerId) {
    Report report = Report.createPending(ownerId, null, null, AccidentType.DISABILITY, "질문", "CRR-1");
    ReflectionTestUtils.setField(report, "id", reportId);
    return report;
  }

  private static CustomerReportDetailRow rowWithAcceptedReview(UUID acceptedReviewId) {
    return new CustomerReportDetailRow(acceptedReviewId, null, null, null, null, null, null, null);
  }

  private static ReportIssue issue(UUID issueId, UUID reportId) {
    ReportIssue issue = BeanUtils.instantiateClass(ReportIssue.class);
    ReflectionTestUtils.setField(issue, "id", issueId);
    ReflectionTestUtils.setField(issue, "reportId", reportId);
    ReflectionTestUtils.setField(issue, "description", "AI 초안 설명");
    return issue;
  }

  @Test
  @DisplayName("리포트가 없으면 사정사가 불러도 REPORT_NOT_FOUND이고 쟁점·상세 행·검수를 읽지 않는다")
  void missingReportThrowsNotFoundBeforeAuthorization() {
    // Given
    UUID reportId = UUID.randomUUID();
    given(reportRepository.findById(reportId)).willReturn(Optional.empty());

    // When & Then: 존재 확인이 인가보다 먼저다 — 열람 권한이 있는 역할이어도 404
    assertThatThrownBy(() -> customerReportDetailReader.read(UUID.randomUUID(), "CERTIFICATED_ADJUSTER", reportId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REPORT_NOT_FOUND));
    then(reportRepository).should(never()).findCustomerReportDetail(any());
    then(reportIssueRepository).shouldHaveNoInteractions();
    then(reportReviewRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("소유자도 사정사도 아니면 FORBIDDEN이고 쟁점·상세 행·검수를 읽지 않는다")
  void strangerIsForbiddenWithoutFurtherReads() {
    // Given
    UUID reportId = UUID.randomUUID();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report(reportId, UUID.randomUUID())));

    // When & Then
    assertThatThrownBy(() -> customerReportDetailReader.read(UUID.randomUUID(), "USER", reportId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    then(reportRepository).should(never()).findCustomerReportDetail(any());
    then(reportIssueRepository).shouldHaveNoInteractions();
    then(reportReviewRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("채택된 검수가 없으면 검수를 읽지 않고 의견 맵은 비어 있다")
  void noAcceptedReviewSkipsReviewLookup() {
    // Given
    UUID ownerId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    Report report = report(reportId, ownerId);
    List<ReportIssue> issues = List.of(issue(UUID.randomUUID(), reportId));
    CustomerReportDetailRow row = rowWithAcceptedReview(null);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(issues);
    given(reportRepository.findCustomerReportDetail(reportId)).willReturn(row);

    // When
    CustomerReportDetailReader.Snapshot snapshot = customerReportDetailReader.read(ownerId, "USER", reportId);

    // Then
    assertThat(snapshot.report()).isSameAs(report);
    assertThat(snapshot.issues()).isSameAs(issues);
    assertThat(snapshot.row()).isSameAs(row);
    assertThat(snapshot.adjusterOpinionByIssueId()).isEmpty();
    then(reportReviewRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("채택된 검수가 있으면 쟁점 오버레이의 사정사 의견을 쟁점 id별 맵으로 풀어 돌려준다")
  void acceptedReviewOpinionsAreUnfoldedIntoMap() {
    // Given
    UUID ownerId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID reviewId = UUID.randomUUID();
    ReportReview review = ReportReview.create(reportId, UUID.randomUUID());
    review.getIssues().add(ReportReviewIssue.create(issueId, null, null, null, IssueReviewStatus.MODIFIED,
        "사정사 의견: 보장 대상", "진단서 근거 보강", null));
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report(reportId, ownerId)));
    given(reportIssueRepository.findAllByReportId(reportId)).willReturn(List.of(issue(issueId, reportId)));
    given(reportRepository.findCustomerReportDetail(reportId)).willReturn(rowWithAcceptedReview(reviewId));
    given(reportReviewRepository.findById(reviewId)).willReturn(Optional.of(review));

    // When
    CustomerReportDetailReader.Snapshot snapshot = customerReportDetailReader.read(ownerId, "USER", reportId);

    // Then
    assertThat(snapshot.adjusterOpinionByIssueId()).isEqualTo(Map.of(issueId, "사정사 의견: 보장 대상"));
  }
}
