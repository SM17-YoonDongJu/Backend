package com.soma.backend.report.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.report.domain.model.ReportIssue;

/** ReportIssue(Report Aggregate 내부 구성요소) Spring Data JPA 리포지토리. */
public interface ReportIssueJpaRepository extends JpaRepository<ReportIssue, UUID> {

  List<ReportIssue> findAllByReportId(UUID reportId);
}
