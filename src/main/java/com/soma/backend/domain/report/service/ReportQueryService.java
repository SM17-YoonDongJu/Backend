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
 * 리포트 조회 유스케이스(design.md §6) — 목록. CQRS 조회 전용.
 * (상세 조회 GET /reports/{reportId}는 develop 검수 대기 상세와 경로가 충돌해 제거했다 — 추후 재개발.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueryService {

  /** 페이지 크기 상한 — page size<=0(PageRequest.of가 IllegalArgumentException)·과대 page size(성능/메모리) 방어. */
  private static final int MAX_PAGE_SIZE = 100;

  private final ReportRepository reportRepository;

  //userId -> 리포트 목록
  public ReportCardListResponse getUserReports(UUID userId, String status, int page, int size) {

    validateStatus(status);

    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), clampSize(size));

    Page<ReportCardRow> rows = reportRepository.findUserReportCards(userId, status, pageable);

    return ReportCardListResponse.from(rows);

  }

  /** size 하한 1(PageRequest.of 예외 방지)·상한 MAX_PAGE_SIZE로 클램프. page 클램프와 동일한 방어 톤. */
  private int clampSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  private void validateStatus(String status) {
    // status 검증
    if (!StringUtils.hasText(status)) {
      return;
    }
    try {

      // 문자열 -> enum 상수 변환
      ReportStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
