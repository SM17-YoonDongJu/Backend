package com.soma.backend.report.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.soma.backend.report.domain.model.ReportHold;

/** ReportHold(junction) 저장소 포트. */
public interface ReportHoldRepository {

  Optional<ReportHold> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  ReportHold save(ReportHold hold);

  void delete(ReportHold hold);
}
