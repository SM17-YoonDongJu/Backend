package com.soma.backend.domain.report.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * API#4 검수 반영 유스케이스. 리더 확정(★#2)에 따라 "채택 사정사" 게이팅은 수행하지 않는다.
 * role == CERTIFICATED_ADJUSTER면(@ActiveAdjuster에서 이미 검증) 허용하고, 본인 REPORT_REVIEWS 행이
 * 없으면 최초 호출 시 멱등 upsert(생성)한다. 쟁점은 ReportReview Aggregate가 소유하며,
 * reportReview.replaceIssues(...)로 교체(같은 AI 쟁점은 인플레이스 갱신)한다.
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

  public ReviewReportResponse review(UUID adjusterId, UUID reportId, ReviewReportRequest request) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

    ReportStatus target = parseStatus(request.status());

    Map<UUID, ReportIssue> reportIssuesById = reportIssueRepository.findAllByReportId(reportId).stream()
        .collect(Collectors.toMap(ReportIssue::getId, issue -> issue));

    List<ReportReviewIssue> desiredIssues = new ArrayList<>();
    if (request.issues() != null) {
      for (ReviewReportRequest.IssueReview issueRequest : request.issues()) {
        desiredIssues.add(toReportReviewIssue(issueRequest, reportIssuesById));
      }
    }

    // 동시성: 스켈레톤 멱등 생성 후 로드 → check-then-insert 경쟁으로 인한 UK 충돌 500 제거.
    reportReviewRepository.insertIfAbsent(reportId, adjusterId);
    ReportReview reportReview = reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    reportReview.updateReviewContent(request.estimateMinAmount(), request.estimateMaxAmount(),
        request.applicableGuarantees(), request.omittedSpecialContract(), request.basisTermsPrecedents(),
        request.review());
    reportReview.replaceIssues(desiredIssues);
    reportReviewRepository.save(reportReview);

    report.applyReviewTransition(target);
    report = reportRepository.save(report);

    return new ReviewReportResponse(
        report.getId(), report.getStatus().name(), reportReview.getId(), reportReview.getStatus().name());
  }

  private ReportStatus parseStatus(String status) {
    if (!StringUtils.hasText(status)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    try {
      return ReportStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
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
        reviewStatus, issueRequest.adjusterOpinion(), issueRequest.modifiedReason(), issueRequest.excludedReason());
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
