package com.soma.backend.domain.report.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.report.dto.ReviewReportRequest;
import com.soma.backend.domain.report.dto.ReviewReportResponse;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.event.ReviewProposalReceivedEvent;
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
@Slf4j
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
  private final ApplicationEventPublisher eventPublisher;

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

    // 상태 전이 검증을 스켈레톤 생성 앞에 둔다: 검수 대상이 아니면(CLOSED·COUNSELING) 여기서 조기 예외.
    // 스켈레톤은 REQUIRES_NEW로 먼저 커밋되므로, '던질 수 있는 것'을 스켈레톤 생성 전에 모두 끝내 고아 행을 막는다.
    report.applyReviewStart();

    // 동시성: 스켈레톤 멱등 생성(별도 트랜잭션) 후 관리 엔티티로 로드 → check-then-insert 경쟁의 UK 충돌 500 제거.
    // 신규 생성 여부(proposalCreated)로 RECEIVED_PROPOSAL 발행을 게이팅한다 — 재수정(이미 존재)·동시 최초 제출
    // (다른 트랜잭션이 먼저 커밋 → UK 충돌)은 false라 무발행(스팸 방지).
    boolean proposalCreated;
    try {
      proposalCreated = reportReviewSkeletonInitializer.ensureExists(reportId, adjusterId);
    } catch (DataIntegrityViolationException ex) {
      proposalCreated = false;
      log.debug("동시 최초 검수 스켈레톤 삽입 감지 — reportId={}, adjusterId={}", reportId, adjusterId);
    }
    ReportReview reportReview = reportReviewRepository.findByReportIdAndAdjusterId(reportId, adjusterId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
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

    report = reportRepository.save(report);

    // 저장 성공 후 발행 — 새 스켈레톤이 생성됐을 때만 "새 제안 도착"을 알린다. AFTER_COMMIT 리스너가
    // 원 트랜잭션 커밋 후 인앱 알림 저장 + 푸시를 수행한다(수신자=리포트 소유자).
    if (proposalCreated) {
      eventPublisher.publishEvent(new ReviewProposalReceivedEvent(report.getUserId(), report.getId(), adjusterId));
    }

    return new ReviewReportResponse(
        report.getId(), report.getStatus().name(), reportReview.getId(), reportReview.getStatus().name(),
        reportReview.getCreatedAt());
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
