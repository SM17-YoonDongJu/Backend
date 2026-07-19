package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.PendingReviewListResponse;
import com.soma.backend.domain.report.dto.PendingReviewSummaryResponse;
import com.soma.backend.domain.report.dto.ReportDetailResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.PendingReviewRow;
import com.soma.backend.domain.report.repository.ReportHoldRepository;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReportReviewStatusRow;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

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
  private static final long DUE_SOON_WINDOW_DAYS = 1L;

  private final ReportRepository reportRepository;
  private final ReportHoldRepository reportHoldRepository;
  private final ReportIssueRepository reportIssueRepository;
  private final ReportReviewRepository reportReviewRepository;

  public PendingReviewSummaryResponse getSummary(UUID adjusterId) {
    long pendingCount = reportRepository.countPending();
    LocalDateTime dueSoonThreshold = LocalDateTime.now().minusDays(SLA_DAYS - DUE_SOON_WINDOW_DAYS);
    long dueSoonCount = reportRepository.countDueSoon(dueSoonThreshold);
    long inProgressCount = reportReviewRepository.countInProgressByAdjusterId(adjusterId);
    return new PendingReviewSummaryResponse(pendingCount, dueSoonCount, inProgressCount);
  }

  public PendingReviewListResponse getPendingReviewList(
      String status, String accidentType, String region, UUID adjusterId, Pageable pageable) {
    ReportStatus statusFilter = parseStatus(status);
    AccidentType accidentTypeFilter = parseAccidentType(accidentType);
    Page<PendingReviewRow> rows =
        reportRepository.findPendingReviewRows(statusFilter, accidentTypeFilter, region, adjusterId, pageable);
    Map<UUID, String> reviewStatusByReportId = loadOwnReviewStatuses(adjusterId, rows);
    return PendingReviewListResponse.from(rows, reviewStatusByReportId);
  }

  /**
   * 목록 페이지의 리포트들에 대해 요청 사정사 본인의 검수 상태(REPORT_REVIEWS.status)를 report_id→status로
   * 한 번에 조회한다. 본인 검수가 없는 리포트는 맵에 없어 응답에서 null이 된다.
   */
  private Map<UUID, String> loadOwnReviewStatuses(UUID adjusterId, Page<PendingReviewRow> rows) {
    List<UUID> reportIds = rows.getContent().stream().map(PendingReviewRow::reportId).toList();
    if (reportIds.isEmpty()) {
      return Map.of();
    }
    return reportReviewRepository.findAdjusterReviewStatuses(adjusterId, reportIds).stream()
        .collect(Collectors.toMap(ReportReviewStatusRow::reportId, row -> row.status().name()));
  }

  /**
   * API#6 — 검수 대기 리포트의 AI 초안 상세 조회. Aggregate 로딩을 우회하고 파생 필드(region·held·개수)를
   * 조합한다. 쟁점은 report당 소량이라 findByReportId로 조회하고, 첨부는 검수 대기 화면용이라
   * REPORTS.documents({name:url} 비정규화 맵)에서 내린다(상세 첨부 REPORT_ATTACHMENTS는 검수 화면 API 담당).
   */
  public ReportDetailResponse getReportDetail(UUID reportId, UUID adjusterId) {
    Report report =
        reportRepository.findById(reportId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (report.getStatus() != ReportStatus.AWAITING_INSPECTION
        && report.getStatus() != ReportStatus.AWAITING_ADOPTION) {
      throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
    }
    List<String> region = reportRepository.findRegionByReportId(reportId);
    boolean held = reportHoldRepository.existsByReportIdAndAdjusterId(reportId, adjusterId);
    List<ReportIssue> issues = reportIssueRepository.findAllByReportId(reportId);
    return ReportDetailResponse.from(report, region, held, issues);
  }

  /** 잘못된 status 필터 값은 조용한 빈 결과 대신 400으로 거른다. 빈 값이면 필터 없음(null). */
  private ReportStatus parseStatus(String status) {
    if (!StringUtils.hasText(status)) {
      return null;
    }
    try {
      return ReportStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }

  /** 잘못된 accidentType 필터 값은 400으로 거른다(DB는 소문자 값). 빈 값이면 필터 없음(null). */
  private AccidentType parseAccidentType(String accidentType) {
    if (!StringUtils.hasText(accidentType)) {
      return null;
    }
    try {
      return AccidentType.from(accidentType);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
