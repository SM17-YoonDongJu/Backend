package com.soma.backend.domain.report.service;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.CreateReportRequest;
import com.soma.backend.domain.report.dto.CreateReportResponse;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportAttachment;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.kafka.OcrJob;
import com.soma.backend.infra.kafka.OcrJobOutboxPort;

/**
 * 리포트 생성·제안 결정 커맨드 유스케이스(design.md §6). createReport는 UserClaim·Report(shell)·첨부를
 * 한 트랜잭션에 저장하고, 문서 1건당 OCR 트리거를 아웃박스로 발행(같은 트랜잭션에서 원자적 적재)한다.
 * OcrJob에 claim/report/attachment 참조 키를 실어, FastAPI가 OCR·AI 결과로 해당 행을 UPDATE하게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportCommandService {

  private static final DateTimeFormatter CASE_NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final UserClaimRepository userClaimRepository;
  private final ReportRepository reportRepository;
  private final ReportAttachmentRepository reportAttachmentRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final OcrJobOutboxPort ocrJobOutboxPort;

  /** POST /reports — 사고 정보 입력 수신 → 저장 → OCR 트리거 발행. 202(비동기). */
  public CreateReportResponse createReport(UUID userId, CreateReportRequest request) {

    if (request.productId() == null || request.accidentType() == null || request.accidentDate() == null) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }

    //accidentType에 따른 claimDetails 생성
    ClaimDetails details =
        ClaimDetails.of(request.accidentType(), request.diagnosis(), request.hospitalizations());

    //DB 저장
    UserClaim claim = userClaimRepository.save(UserClaim.create(
        userId, request.productId(), request.offeredAmount(), request.accidentDate(),
        request.accidentType(), details, request.question(), request.description(),
        request.additionalInformation()));

    //Reports 테이블 스켈레톤 저장
    Report report = reportRepository.save(Report.createPending(
        userId, request.productId(), claim.getId(), request.accidentType(), request.question(),
        generateCaseNo()));

    //Document
    List<CreateReportRequest.Document> documents =
        request.documents() == null ? List.of() : request.documents();

    int docTotal = documents.size();

    // Document 별 반복문 (doc_index 1-based, doc_total로 FastAPI가 OCR 완료(fan-in) 판별)
    for (int i = 0; i < documents.size(); i++) {
      CreateReportRequest.Document document = documents.get(i);

      //contentType 매핑
      String contentType = toContentType(document.fileType());

      //report_attachments 테이블 스켈레톤 저장
      ReportAttachment attachment = reportAttachmentRepository.save(ReportAttachment.of(
          report.getId(), document.name(), document.s3Url(), contentType, document.reportType()));

      //document 1건 단위 ocrjob 발행 (report/attachment 참조 + 문서 순번·총개수 포함)
      ocrJobOutboxPort.enqueue(new OcrJob(
          UUID.randomUUID().toString(),
          toS3Key(document.s3Url()),
          contentType,
          userId.toString(),
          document.reportType(),
          claim.getId().toString(),
          report.getId().toString(),
          attachment.getId().toString(),
          i + 1,
          docTotal,
          Instant.now().toString()));
    }

    return new CreateReportResponse(report.getId(), report.getStatus());
  }

  /** PATCH /reports/{reportId}/proposals/{proposalId} — 본인 리포트의 특정 제안 채택/거절. */
  public ProposalDecisionResponse decide(UUID userId, UUID reportId, UUID proposalId, String status) {
    ReviewStatus decision = parseDecision(status);

    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    ReportReview review = reportReviewRepository.findById(proposalId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));
    if (!review.getReportId().equals(reportId)) {
      throw new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND);
    }

    if (decision == ReviewStatus.ACCEPTED) {
      review.accept();
      report.accept(review.getAdjusterId());
    } else {
      review.reject();
    }

    return new ProposalDecisionResponse(
        reportId, review.getId(), review.getAdjusterId(), report.getStatus(), review.getStatus());
  }

  /** ACCEPTED/REJECTED만 허용 — 그 외 값은 400. */
  private ReviewStatus parseDecision(String status) {
    if (!StringUtils.hasText(status)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    ReviewStatus decision;
    try {
      decision = ReviewStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
    if (decision != ReviewStatus.ACCEPTED && decision != ReviewStatus.REJECTED) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
    return decision;
  }

  /** 사람용 사건번호 yyyyMMdd-NNN. 당일 시퀀스(경합 시 case_no UNIQUE 제약이 최종 방어선). */
  private String generateCaseNo() {
    String day = LocalDate.now().format(CASE_NO_DAY);
    long sequence = reportRepository.countByCaseNoStartingWith(day + "-") + 1;
    return String.format("%s-%03d", day, sequence);
  }

  private String toS3Key(String s3Url) {
    if (!StringUtils.hasText(s3Url)) {
      return null;
    }
    String path = URI.create(s3Url).getPath();
    return path == null ? s3Url : path.replaceFirst("^/", "");
  }

  private String toContentType(String fileType) {
    if (!StringUtils.hasText(fileType)) {
      return null;
    }
    String normalized = fileType.toLowerCase().replaceFirst("^\\.", "");
    return switch (normalized) {
      case "pdf" -> "application/pdf";
      case "jpg", "jpeg" -> "image/jpeg";
      case "png" -> "image/png";
      case "tiff", "tif" -> "image/tiff";
      default -> null;
    };
  }
}
