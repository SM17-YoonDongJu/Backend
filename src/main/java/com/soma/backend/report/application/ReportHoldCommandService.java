package com.soma.backend.report.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.report.application.dto.HoldToggleResult;
import com.soma.backend.report.domain.model.ReportHold;
import com.soma.backend.report.domain.repository.ReportHoldRepository;
import com.soma.backend.report.domain.repository.ReportRepository;

/** API#3 사정사별 보류 토글 유스케이스. 단일 Aggregate(ReportHold) 수정. */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportHoldCommandService {

  private final ReportRepository reportRepository;
  private final ReportHoldRepository reportHoldRepository;

  public HoldToggleResult toggle(UUID reportId, UUID adjusterId) {
    reportRepository.findById(reportId).orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));

    Optional<ReportHold> existing = reportHoldRepository.findByReportIdAndAdjusterId(reportId, adjusterId);
    if (existing.isPresent()) {
      reportHoldRepository.delete(existing.get());
      return new HoldToggleResult(reportId, false);
    }
    reportHoldRepository.save(new ReportHold(reportId, adjusterId));
    return new HoldToggleResult(reportId, true);
  }
}
