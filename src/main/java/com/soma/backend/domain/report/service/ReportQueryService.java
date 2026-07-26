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

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.CustomerReportDetailRow;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportIssueRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 고객 리포트 조회 유스케이스(design.md §6) — 목록·상세. CQRS 조회 전용. 소유자(userId=principal) 스코프로
 * 본인 리포트를 전 상태(AWAITING_INSPECTION 포함) 반환한다. 상세 조회는 소유자(USER) 또는 사정사만 허용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {

  /** 페이지 크기 상한 — page size<=0(PageRequest.of가 IllegalArgumentException)·과대 page size(성능/메모리) 방어. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ReportRepository reportRepository;
  private final ReportIssueRepository reportIssueRepository;

  /** userId 소유 리포트 카드 목록. status는 옵션 필터(REPORTS.status), page는 1-based. */
  public ReportCardListResponse getUserReports(UUID userId, String status, int page, int size) {
    ReportStatus statusFilter = parseStatus(status);
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));
    Page<ReportCardRow> rows = reportRepository.findUserReportCards(userId, statusFilter, pageable);
    return ReportCardListResponse.from(rows);
  }

  /**
   * 고객 리포트 상세(GET /reports/{reportId}). 인가는 리포트 소유자(USER, report.userId=principal) 또는
   * 사정사(CERTIFICATED/UNCERTIFICATED_ADJUSTER)만 허용하고, 둘 다 아니면 403 FORBIDDEN을 던진다.
   * 존재하지 않는 리포트는 404 REPORT_NOT_FOUND. 확정 배열·검수 코멘트·담당 사정사는 크로스-애그리거트
   * 읽기 모델로, 쟁점은 report_issues에서 조립한다.
   */
  public CustomerReportDetailResponse getReportDetail(UUID userId, String role, UUID reportId) {
    Report report = reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    if (!report.isOwnedBy(userId) && !isAdjuster(role)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    List<ReportIssue> issues = reportIssueRepository.findAllByReportId(reportId);
    CustomerReportDetailRow row = reportRepository.findCustomerReportDetail(reportId);
    return CustomerReportDetailResponse.from(report, issues, row);
  }

  /** 사정사 역할(자격 유무 무관)이면 임의 리포트 상세 조회를 허용한다(파트너 draft-preview 부분집합 소비). */
  private boolean isAdjuster(String role) {
    return "CERTIFICATED_ADJUSTER".equals(role) || "UNCERTIFICATED_ADJUSTER".equals(role);
  }

  /** size 하한 1(PageRequest.of 예외 방지)·상한 MAX_PAGE_SIZE로 클램프. page 클램프와 동일한 방어 톤. */
  private int clampSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  /** status 파라미터를 enum으로 파싱한다. 빈 값이면 필터 없음(null), 알 수 없는 값이면 400 VALIDATION_ERROR. */
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
}
