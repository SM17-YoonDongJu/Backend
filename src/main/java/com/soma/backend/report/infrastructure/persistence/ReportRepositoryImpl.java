package com.soma.backend.report.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.model.Report;
import com.soma.backend.report.domain.repository.ReportRepository;

/** ReportRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportRepositoryImpl implements ReportRepository {

  private final ReportJpaRepository reportJpaRepository;

  @Override
  public Report save(Report report) {
    return reportJpaRepository.save(report);
  }

  @Override
  public Optional<Report> findById(UUID id) {
    return reportJpaRepository.findById(id);
  }

  @Override
  public List<Report> findAllById(List<UUID> ids) {
    return reportJpaRepository.findAllByIdIn(ids);
  }
}
