package com.soma.backend.domain.report.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.dto.ReportDetailResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportDetailRow;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 리포트 조회 유스케이스(design.md §6) — 목록/상세. CQRS 조회 전용. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {

  private final ReportRepository reportRepository;
  private final UserClaimRepository userClaimRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;

  public ReportCardListResponse getUserReports(UUID userId, String status, int page, int size) {
    validateStatus(status);
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
    Page<ReportCardRow> rows = reportRepository.findUserReportCards(userId, status, pageable);
    return ReportCardListResponse.from(rows);
  }

  public ReportDetailResponse getDetail(UUID userId, UUID reportId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    ReportDetailRow join = reportRepository.findDetailRow(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

    UserClaim claim = report.getClaimId() == null ? null
        : userClaimRepository.findById(report.getClaimId()).orElse(null);
    List<ReportDetailResponse.Issue> issues = reportIssueRepository.findAllByReportId(reportId).stream()
        .map(this::toIssue).toList();
    List<ReportDetailResponse.Attachment> attachments =
        reportAttachmentRepository.findAllByReportId(reportId).stream().map(this::toAttachment).toList();

    boolean masked = Boolean.TRUE.equals(report.getIsMasked());
    ReportDetailResponse.Client client = new ReportDetailResponse.Client(
        masked ? maskName(join.getClientNickname()) : join.getClientNickname(),
        join.getClientGender(), join.getClientRegion());

    ClaimDetails details = claim == null ? null : claim.getDetails();
    return new ReportDetailResponse(
        report.getId(),
        report.getStatus().name(),
        report.getAccidentType() == null ? null : report.getAccidentType().getValue(),
        report.getTreatment(),
        report.getClaimedMinAmount(),
        report.getClaimedMaxAmount(),
        report.getOfferedAmount(),
        report.getApplicableGuarantees(),
        report.getOmittedSpecialContract(),
        report.getBasisTermsPrecedents(),
        report.getConfidenceLevel(),
        issues,
        report.getQuestion(),
        report.getCaseNo(),
        claim == null ? null : claim.getAccidentDate(),
        insuranceName(join),
        details == null ? null : details.diagnosis(),
        details == null ? null : details.hospitalizations(),
        claim == null ? null : claim.getDescription(),
        client,
        report.getIsMasked(),
        attachments,
        report.getAdjusterId(),
        join.getAdjusterNickname());
  }

  private void validateStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return;
    }
    try {
      ReportStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }

  private ReportDetailResponse.Issue toIssue(ReportIssue issue) {
    return new ReportDetailResponse.Issue(
        issue.getId(), issue.getTitle(), issue.getDescription(), issue.getAiStatus(),
        issue.getTags(), issue.getImpactAmount());
  }

  private ReportDetailResponse.Attachment toAttachment(ReportAttachment attachment) {
    return new ReportDetailResponse.Attachment(
        attachment.getId(), attachment.getName(), attachment.getMimeType(), attachment.getPageCount(),
        attachment.getUrl(), attachment.getIssuedBy(), attachment.getIssuedAt(), attachment.getAiSummary());
  }

  private String insuranceName(ReportDetailRow join) {
    if (join.getInsurerName() == null && join.getProductName() == null) {
      return null;
    }
    if (join.getInsurerName() == null) {
      return join.getProductName();
    }
    if (join.getProductName() == null) {
      return join.getInsurerName();
    }
    return join.getInsurerName() + " · " + join.getProductName();
  }

  private String maskName(String name) {
    if (!StringUtils.hasText(name) || name.length() < 2) {
      return name;
    }
    if (name.length() == 2) {
      return name.charAt(0) + "O";
    }
    return name.charAt(0) + "O".repeat(name.length() - 2) + name.charAt(name.length() - 1);
  }
}
