package com.soma.backend.domain.report.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.repository.ReportReviewRepository;

/**
 * ReportReview 스켈레톤(최초 검수 행) 멱등 확보. 별도 트랜잭션(REQUIRES_NEW)에서 생성하므로 동시 최초 제출로
 * UK(report_id, adjuster_id)가 충돌해도 이 트랜잭션만 롤백되고 호출자 트랜잭션은 오염되지 않는다.
 * 엔티티 생성(new ReportReview)은 리포지토리가 아니라 이 application 계층에서 일어난다.
 */
@Component
@RequiredArgsConstructor
public class ReportReviewSkeletonInitializer {

  private final ReportReviewRepository reportReviewRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void ensureExists(UUID reportId, UUID adjusterId) {
    if (!reportReviewRepository.existsByReportIdAndAdjusterId(reportId, adjusterId)) {
      reportReviewRepository.save(new ReportReview(reportId, adjusterId));
    }
  }
}
