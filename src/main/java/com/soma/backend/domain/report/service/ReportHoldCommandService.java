package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.HoldResponse;
import com.soma.backend.domain.report.repository.ReportHoldRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * API#3 사정사별 보류 추가 유스케이스. 보류 취소는 없으므로 토글이 아닌 명시적 추가(add)만 두고
 * 멱등하게 처리한다(동시 요청에도 500 없음).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportHoldCommandService {

  private final ReportRepository reportRepository;
  private final ReportHoldRepository reportHoldRepository;

  /** 보류 추가(멱등). 이미 보류 중이어도 held=true. */
  public HoldResponse addHold(UUID reportId, UUID adjusterId) {
    requireReport(reportId);
    reportHoldRepository.insertIfAbsent(reportId, adjusterId);
    return new HoldResponse(reportId, true);
  }

  private void requireReport(UUID reportId) {
    if (!reportRepository.existsById(reportId)) {
      throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
    }
  }
}
