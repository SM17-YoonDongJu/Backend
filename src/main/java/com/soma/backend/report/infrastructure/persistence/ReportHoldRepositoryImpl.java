package com.soma.backend.report.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.model.ReportHold;
import com.soma.backend.report.domain.repository.ReportHoldRepository;

/** ReportHoldRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportHoldRepositoryImpl implements ReportHoldRepository {

  private final ReportHoldJpaRepository reportHoldJpaRepository;

  @Override
  public Optional<ReportHold> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId) {
    return reportHoldJpaRepository.findByReportIdAndAdjusterId(reportId, adjusterId);
  }

  @Override
  public ReportHold save(ReportHold hold) {
    return reportHoldJpaRepository.save(hold);
  }

  @Override
  public void delete(ReportHold hold) {
    reportHoldJpaRepository.delete(hold);
  }
}
