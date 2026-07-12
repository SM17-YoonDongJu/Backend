package com.soma.backend.domain.report.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReviewReportRequest;
import com.soma.backend.domain.report.dto.ReviewReportResponse;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * API#4 검수 반영 유스케이스. 리더 확정(★#2)에 따라 "채택 사정사" 게이팅은 수행하지 않는다.
 * role 인가(CERTIFICATED_ADJUSTER만 허용)는 컨트롤러의 {@code @PreAuthorize("hasRole('CERTIFICATED_ADJUSTER')")}가
 * 보증하므로 이 서비스는 role을 재검증하지 않는다. 본인 REPORT_REVIEWS 행이 없으면 최초 호출 시
 * 멱등 생성(스켈레톤)한다. 쟁점은 ReportReview Aggregate가 소유하며,
 * reportReview.upsertIssue(...)로 부분 반영(보낸 쟁점만 갱신/추가, 삭제 없음)한다.
 * 검수 내용(금액·보장·쟁점·피드백)은 REPORT_REVIEWS에만 저장하고 REPORTS는 status 전이(applyReviewStart)만
 * 반영한다 — target은 클라가 지정하지 않고 현재 status에서 서버가 파생한다(A8 격리).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportReviewCommandService {

  private static final Set<IssueReviewStatus> REQUIRES_EXISTING_ISSUE =
      Set.of(IssueReviewStatus.ACCEPTED, IssueReviewStatus.MODIFIED, IssueReviewStatus.EXCLUDED);

  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewSkeletonInitializer reportReviewSkeletonInitializer;

  public ReviewReportResponse review(UUID adjusterId, UUID reportId, ReviewReportRequest request) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

    Map<UUID, ReportIssue> reportIssuesById = reportIssueRepository.findAllByReportId(reportId).stream()
        .collect(Collectors.toMap(ReportIssue::getId, issue -> issue));

    // 쟁점 검증·빌드를 DB 쓰기 전에 수행 — 실패 시 조기 예외.
    List<ReportReviewIssue> desiredIssues = new ArrayList<>();
    if (request.issues() != null) {
      for (ReviewReportRequest.IssueReview issueRequest : request.issues()) {
        desiredIssues.add(toReportReviewIssue(issueRequest, reportIssuesById));
      }
    }

    // 동시성: 스켈레톤 멱등 생성(별도 트랜잭션) 후 관리 엔티티로 로드 → check-then-insert 경쟁의 UK 충돌 500 제거.
    ReportReview reportReview = getOrCreateSkeleton(reportId, adjusterId);
    reportReview.updateReviewContent(request.estimateMinAmount(), request.estimateMaxAmount(),
        request.applicableGuarantees(), request.omittedSpecialContract(), request.basisTermsPrecedents(),
        request.review());
    // 부분 반영(upsert): 보낸 쟁점만 갱신/추가, 안 보낸 쟁점은 유지(삭제 없음).
    if (request.issues() != null) {
      List<ReviewReportRequest.IssueReview> issueRequests = request.issues();
      for (int idx = 0; idx < desiredIssues.size(); idx++) {
        reportReview.upsertIssue(issueRequests.get(idx).reviewIssueId(), desiredIssues.get(idx));
      }
    }
    reportReviewRepository.save(reportReview);

    report.applyReviewStart();
    report = reportRepository.save(report);

    return new ReviewReportResponse(
        report.getId(), report.getStatus().name(), reportReview.getId(), reportReview.getStatus().name());
  }

  /**
   * 검수 스켈레톤을 멱등 확보한 뒤 관리 엔티티로 로드한다. 동시 최초 제출로 UK가 충돌하면
   * REQUIRES_NEW 트랜잭션에서만 롤백되어 {@link DataIntegrityViolationException}으로 전파되며, 이를 멱등으로 흡수한다.
   */
  private ReportReview getOrCreateSkeleton(UUID reportId, UUID adjusterId) {
    try {
      reportReviewSkeletonInitializer.ensureExists(reportId, adjusterId);
    } catch (DataIntegrityViolationException ex) {
      // 동시 최초 제출: 다른 트랜잭션이 먼저 스켈레톤을 커밋했다. 멱등 처리로 무시하고 아래에서 로드한다.
    }
    return reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
  }

  private ReportReviewIssue toReportReviewIssue(
      ReviewReportRequest.IssueReview issueRequest, Map<UUID, ReportIssue> reportIssuesById) {
    IssueReviewStatus reviewStatus = parseIssueReviewStatus(issueRequest.reviewStatus());

    if (reviewStatus == IssueReviewStatus.ADDED) {
      if (!StringUtils.hasText(issueRequest.title()) || !StringUtils.hasText(issueRequest.description())) {
        throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
      }
    } else if (REQUIRES_EXISTING_ISSUE.contains(reviewStatus)) {
      if (issueRequest.issueId() == null || !reportIssuesById.containsKey(issueRequest.issueId())) {
        throw new BusinessException(ErrorCode.REPORT_ISSUE_NOT_FOUND);
      }
    }

    if (reviewStatus == IssueReviewStatus.MODIFIED && !StringUtils.hasText(issueRequest.modifiedReason())) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    if (reviewStatus == IssueReviewStatus.EXCLUDED && !StringUtils.hasText(issueRequest.excludedReason())) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    return new ReportReviewIssue(issueRequest.issueId(), issueRequest.title(), issueRequest.description(),
        issueRequest.impactAmount(), reviewStatus, issueRequest.adjusterOpinion(), issueRequest.modifiedReason(),
        issueRequest.excludedReason());
  }

  private IssueReviewStatus parseIssueReviewStatus(String reviewStatus) {
    if (!StringUtils.hasText(reviewStatus)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    try {
      return IssueReviewStatus.valueOf(reviewStatus);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
