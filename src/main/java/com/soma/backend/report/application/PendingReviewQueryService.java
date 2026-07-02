package com.soma.backend.report.application;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.application.dto.PendingReviewItemResult;
import com.soma.backend.report.application.dto.PendingReviewSummaryResult;
import com.soma.backend.report.domain.repository.PendingReviewRow;
import com.soma.backend.report.domain.repository.ReportQueryRepository;

/**
 * API#1(요약)·API#2(목록) 조회 전용 유스케이스(CQRS). Aggregate 로딩을 우회해 파생 필드를 조회한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PendingReviewQueryService {

  /** 검수 SLA(접수 후 검수 완료 기한). 기획 미확정 상태의 잠정값 — 리더 확정 시 조정 필요. */
  private static final long SLA_DAYS = 7L;

  /** 마감 임박으로 간주하는 SLA 잔여 여유 기간. SLA_DAYS 이내 이 값만큼 남았으면 due_soon. */
  private static final long DUE_SOON_WINDOW_DAYS = 2L;

  private final ReportQueryRepository reportQueryRepository;

  public PendingReviewSummaryResult getSummary() {
    long pendingCount = reportQueryRepository.countPending();
    LocalDateTime dueSoonThreshold = LocalDateTime.now().minusDays(SLA_DAYS - DUE_SOON_WINDOW_DAYS);
    long dueSoonCount = reportQueryRepository.countDueSoon(dueSoonThreshold);
    return new PendingReviewSummaryResult(pendingCount, dueSoonCount);
  }

  public Page<PendingReviewItemResult> getPendingReviewList(
      String status, String accidentType, String region, UUID adjusterId, Pageable pageable) {
    Page<PendingReviewRow> rows =
        reportQueryRepository.findPendingReviewRows(status, accidentType, region, adjusterId, pageable);
    return rows.map(this::toResult);
  }

  private PendingReviewItemResult toResult(PendingReviewRow row) {
    long claimedMax = row.getClaimedMaxAmount() == null ? 0L : row.getClaimedMaxAmount();
    long offered = row.getOfferedAmount() == null ? 0L : row.getOfferedAmount();
    long issueCount = row.getIssueCount() == null ? 0L : row.getIssueCount();
    boolean held = Boolean.TRUE.equals(row.getHeld());
    return new PendingReviewItemResult(
        row.getReportId(),
        row.getCaseNo(),
        row.getTitle(),
        row.getAccidentType(),
        row.getRegion(),
        row.getStatus(),
        row.getClaimedMinAmount(),
        row.getClaimedMaxAmount(),
        row.getOfferedAmount(),
        claimedMax - offered,
        issueCount,
        held);
  }
}
