package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportCardRow;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 고객 리포트 조회 유스케이스(design.md §6) — 목록. CQRS 조회 전용. 소유자(userId=principal) 스코프로
 * 본인 리포트를 전 상태(AWAITING_INSPECTION 포함) 반환한다 — 타인 리포트는 노출하지 않는다.
 * (상세 조회 GET /reports/{reportId}는 develop 검수 대기 상세와 경로가 충돌해 별도 단계에서 처리한다.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {

  /** 페이지 크기 상한 — page size<=0(PageRequest.of가 IllegalArgumentException)·과대 page size(성능/메모리) 방어. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ReportRepository reportRepository;

  /** userId 소유 리포트 카드 목록. status는 옵션 필터(REPORTS.status), page는 1-based. */
  public ReportCardListResponse getUserReports(UUID userId, String status, int page, int size) {
    ReportStatus statusFilter = parseStatus(status);
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));
    Page<ReportCardRow> rows = reportRepository.findUserReportCards(userId, statusFilter, pageable);
    return ReportCardListResponse.from(rows);
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
