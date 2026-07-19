package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.UserActivitySummaryResponse;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * GET /users/me/activity-summary 조회 전용 유스케이스(CQRS). 요청 사용자(리포트 소유자) 기준으로
 * 리포트·제안·상담·종결 카운트를 집계한다. 데이터가 전부 report·review 도메인이라 이 컨텍스트에 둔다
 * (AdjusterHomeQueryService 관례).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserActivitySummaryQueryService {

  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;

  public UserActivitySummaryResponse getActivitySummary(UUID userId) {
    long reportCount = reportRepository.countByUserId(userId);
    long proposalCount = reportReviewRepository.countProposalsByReportOwner(userId);
    long consultCount = reportReviewRepository.countConsultsByReportOwner(userId);
    long closedCount = reportRepository.countClosedByUserId(userId);
    return new UserActivitySummaryResponse(reportCount, proposalCount, consultCount, closedCount);
  }
}
