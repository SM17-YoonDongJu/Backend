package com.soma.backend.report.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.soma.backend.report.domain.model.Report;

/** Report Aggregate 저장소 포트. */
public interface ReportRepository {

  Report save(Report report);

  Optional<Report> findById(UUID id);

  List<Report> findAllById(List<UUID> ids);
}
