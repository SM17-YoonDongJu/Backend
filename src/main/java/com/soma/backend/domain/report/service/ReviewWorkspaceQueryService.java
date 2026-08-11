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
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReviewContextRow;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.S3UploadService;

/**
 * 검수 화면(편집 워크스페이스) 집계 조회 유스케이스(CQRS). AI 초안(REPORTS/REPORT_ISSUES)·사정사 작업본
 * (REPORT_REVIEWS/REPORT_ISSUES_REVIEWS)·상세 첨부(REPORT_ATTACHMENTS)·의뢰인 맥락을 한 응답으로 조립한다.
 * 진단·입원 맥락은 USER_CLAIMS.details(sealed ClaimDetails)에서 구조 그대로 읽는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewWorkspaceQueryService {

  private final ReportRepository reportRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;
  private final UserClaimRepository userClaimRepository;
  private final S3UploadService s3UploadService;

  public ReviewWorkspaceResponse getReviewWorkspace(UUID reportId, UUID adjusterId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    // 상세 조회와 동일한 노출 정책: 검수 단계(검수 대기·채택 대기)가 아니면 존재를 숨겨 404로 응답한다.
    if (!report.isInReviewPhase()) {
      throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
    }
    ReviewContextRow context = reportRepository.findReviewContext(reportId);
    List<String> region = reportRepository.findRegionByReportId(reportId);
    // additional_information은 암호화(bytea) 컬럼이라 native 프로젝션이 아니라 컨버터가 적용되는
    // UserClaim 엔티티에서 읽는다. details와 함께 쓰므로 claim 자체를 들고 온다.
    UserClaim claim = report.getClaimId() == null ? null
        : userClaimRepository.findById(report.getClaimId()).orElse(null);
    ClaimDetails claimDetails = claim == null ? null : claim.getDetails();
    String additionalInformation = claim == null ? null : claim.getAdditionalInformation();
    List<ReportIssue> aiIssues = reportIssueRepository.findAllByReportId(reportId);
    ReportReview review = reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId).orElse(null);
    List<ReportAttachment> attachments = reportAttachmentRepository.findAllByReportId(reportId);
    return ReviewWorkspaceResponse.from(
        report, context, region, claimDetails, additionalInformation, aiIssues, review, attachments,
        s3UploadService::presignedGetUrl);
  }
}
