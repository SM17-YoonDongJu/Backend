package com.soma.backend.domain.report.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.report.entity.ReportHold;

/** ReportHold(junction) Spring Data JPA 리포지토리. */
public interface ReportHoldRepository extends JpaRepository<ReportHold, UUID> {

  Optional<ReportHold> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);
}
