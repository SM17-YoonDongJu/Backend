package com.soma.backend.report.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.report.domain.model.ReportHold;

/** ReportHold Spring Data JPA 리포지토리. */
public interface ReportHoldJpaRepository extends JpaRepository<ReportHold, UUID> {

  Optional<ReportHold> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);
}
