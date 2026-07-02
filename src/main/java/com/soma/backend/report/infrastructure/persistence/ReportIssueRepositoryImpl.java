package com.soma.backend.report.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.model.ReportIssue;
import com.soma.backend.report.domain.repository.ReportIssueRepository;

/** ReportIssueRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportIssueRepositoryImpl implements ReportIssueRepository {

  private final ReportIssueJpaRepository reportIssueJpaRepository;

  @Override
  public List<ReportIssue> findAllByReportId(UUID reportId) {
    return reportIssueJpaRepository.findAllByReportId(reportId);
  }
}
