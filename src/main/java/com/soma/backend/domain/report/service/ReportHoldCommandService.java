package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.HoldReportRequest;
import com.soma.backend.domain.report.dto.HoldResponse;
import com.soma.backend.domain.report.entity.HoldReason;
import com.soma.backend.domain.report.repository.ReportHoldRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * API#3 사정사별 보류 추가 유스케이스. 보류 취소는 없으므로 토글이 아닌 명시적 추가(add)만 두고
 * 멱등하게 처리한다(동시 요청·재보류에도 500 없이 최신 사유로 갱신). 보류 사유(reason)는 필수이며,
 * reason이 {@link HoldReason#OTHER}(직접 입력)면 상세 텍스트(reasonDetail)도 필수다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportHoldCommandService {

  private final ReportRepository reportRepository;
  private final ReportHoldRepository reportHoldRepository;

  /** 보류 추가(멱등). 이미 보류 중이어도 최신 사유로 갱신하고 held=true. */
  public HoldResponse addHold(UUID reportId, UUID adjusterId, HoldReportRequest request) {
    requireReport(reportId);
    HoldReason reason = parseReason(request.reason());
    String reasonDetail = resolveReasonDetail(reason, request.reasonDetail());
    reportHoldRepository.upsertHold(reportId, adjusterId, reason.name(), reasonDetail);
    return new HoldResponse(reportId, true, reason.name(), reasonDetail);
  }

  private void requireReport(UUID reportId) {
    if (!reportRepository.existsById(reportId)) {
      throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
    }
  }

  private HoldReason parseReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    try {
      return HoldReason.valueOf(reason);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }

  /** OTHER면 상세 텍스트 필수. 프리셋 사유는 선택이며, 공백만 있으면 null로 정규화한다. */
  private String resolveReasonDetail(HoldReason reason, String reasonDetail) {
    if (reason == HoldReason.OTHER && !StringUtils.hasText(reasonDetail)) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    return StringUtils.hasText(reasonDetail) ? reasonDetail.trim() : null;
  }
}
