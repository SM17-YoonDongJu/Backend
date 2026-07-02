package com.soma.backend.report.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.report.application.dto.ReviewReportCommand;
import com.soma.backend.report.application.dto.ReviewReportResult;
import com.soma.backend.report.domain.model.IssueReviewStatus;
import com.soma.backend.report.domain.model.Report;
import com.soma.backend.report.domain.model.ReportIssue;
import com.soma.backend.report.domain.model.ReportReview;
import com.soma.backend.report.domain.model.ReportReviewIssue;
import com.soma.backend.report.domain.model.ReportStatus;
import com.soma.backend.report.domain.repository.ReportIssueRepository;
import com.soma.backend.report.domain.repository.ReportRepository;
import com.soma.backend.report.domain.repository.ReportReviewIssueRepository;
import com.soma.backend.report.domain.repository.ReportReviewRepository;

/**
 * API#4 검수 반영 유스케이스. 리더 확정(★#2)에 따라 "채택 사정사" 게이팅은 수행하지 않는다.
 * role == CERTIFICATED_ADJUSTER면(@ActiveAdjuster에서 이미 검증) 허용하고, 본인 REPORT_REVIEWS 행이
 * 없으면 최초 호출 시 upsert(생성)한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportReviewCommandService {

  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewIssueRepository reportReviewIssueRepository;

  public ReviewReportResult review(ReviewReportCommand command) {
    Report report = reportRepository.findById(command.reportId())
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

    ReportStatus target = parseStatus(command.status());

    Map<UUID, ReportIssue> reportIssuesById = reportIssueRepository.findAllByReportId(command.reportId()).stream()
        .collect(Collectors.toMap(ReportIssue::getId, issue -> issue));

    List<ReportReviewIssue> issuesToPersist = new ArrayList<>();
    if (command.issues() != null) {
      for (ReviewReportCommand.IssueReviewCommand issueCommand : command.issues()) {
        issuesToPersist.add(toReportReviewIssue(issueCommand, reportIssuesById));
      }
    }

    ReportReview reportReview = reportReviewRepository
        .findByReportIdAndAdjusterId(command.reportId(), command.adjusterId())
        .orElseGet(() -> new ReportReview(command.reportId(), command.adjusterId()));
    reportReview.updateReviewContent(command.applicableGuarantees(), command.omittedSpecialContract(),
        command.review());
    reportReview = reportReviewRepository.save(reportReview);

    UUID reportReviewId = reportReview.getId();
    reportReviewIssueRepository.deleteAllByReportReviewId(reportReviewId);
    if (!issuesToPersist.isEmpty()) {
      List<ReportReviewIssue> rebound = issuesToPersist.stream()
          .map(issue -> new ReportReviewIssue(reportReviewId, issue.getReportIssueId(),
              issue.getReviewStatus(), issue.getAdjusterOpinion(), issue.getModifiedReason(),
              issue.getExcludedReason()))
          .collect(Collectors.toList());
      reportReviewIssueRepository.saveAll(rebound);
    }

    report.applyReviewTransition(target);
    report = reportRepository.save(report);

    return new ReviewReportResult(
        report.getId(), report.getStatus().name(), reportReview.getId(), reportReview.getOutcome().name());
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
      ReviewReportCommand.IssueReviewCommand issueCommand, Map<UUID, ReportIssue> reportIssuesById) {
    if (!reportIssuesById.containsKey(issueCommand.issueId())) {
      throw new BusinessException(ErrorCode.REPORT_ISSUE_NOT_FOUND);
    }
    IssueReviewStatus reviewStatus = parseIssueReviewStatus(issueCommand.reviewStatus());
    if (reviewStatus == IssueReviewStatus.MODIFIED && !StringUtils.hasText(issueCommand.modifiedReason())) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    if (reviewStatus == IssueReviewStatus.EXCLUDED && !StringUtils.hasText(issueCommand.excludedReason())) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    return new ReportReviewIssue(null, issueCommand.issueId(), reviewStatus, issueCommand.adjusterOpinion(),
        issueCommand.modifiedReason(), issueCommand.excludedReason());
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
