package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReviewWorkspaceResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReviewContextRow;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 검수 화면(편집 워크스페이스) 집계 조회 유스케이스(CQRS). AI 초안(REPORTS/REPORT_ISSUES)·사정사 작업본
 * (REPORT_REVIEWS/REPORT_REVIEW_ISSUES)·상세 첨부(REPORT_ATTACHMENTS)·의뢰인 맥락을 한 응답으로 조립한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewWorkspaceQueryService {

  private final ReportRepository reportRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;

  public ReviewWorkspaceResponse getReviewWorkspace(UUID reportId, UUID adjusterId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    ReviewContextRow context = reportRepository.findReviewContext(reportId);
    List<ReportIssue> aiIssues = reportIssueRepository.findAllByReportId(reportId);
    ReportReview review = reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId).orElse(null);
    List<ReportAttachment> attachments = reportAttachmentRepository.findAllByReportId(reportId);
    return ReviewWorkspaceResponse.from(report, context, aiIssues, review, attachments);
  }
}
