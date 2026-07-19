package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.HoldReason;
import com.soma.backend.domain.report.entity.ReportHold;
import com.soma.backend.domain.report.repository.ReportHoldRepository;

/**
 * ReportHold(보류 행) 멱등 확보. 별도 트랜잭션(REQUIRES_NEW)에서 생성하므로 동시 최초 보류로
 * UK(adjuster_id, report_id)가 충돌해도 이 트랜잭션만 롤백되고 호출자 트랜잭션은 오염되지 않는다.
 * 이미 있으면 아무것도 하지 않고, 사유(reason) 갱신은 호출자가 관리 엔티티를 로드해 수행한다.
 * 엔티티 생성(new ReportHold)은 리포지토리가 아니라 이 application 계층에서 일어난다.
 */
@Component
@RequiredArgsConstructor
public class ReportHoldInitializer {

  private final ReportHoldRepository reportHoldRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void ensureExists(UUID reportId, UUID adjusterId, HoldReason reason, String reasonDetail) {
    if (!reportHoldRepository.existsByReportIdAndAdjusterId(reportId, adjusterId)) {
      reportHoldRepository.save(new ReportHold(reportId, adjusterId, reason, reasonDetail));
    }
  }
}
