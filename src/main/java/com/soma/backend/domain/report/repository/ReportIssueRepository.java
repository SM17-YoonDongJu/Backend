package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.report.entity.ReportIssue;

/** ReportIssue(Report Aggregate 내부 구성요소) Spring Data JPA 리포지토리. */
public interface ReportIssueRepository extends JpaRepository<ReportIssue, UUID> {

  List<ReportIssue> findAllByReportId(UUID reportId);
}
