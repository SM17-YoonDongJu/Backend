package com.soma.backend.report.domain.repository;

import java.util.List;
import java.util.UUID;

import com.soma.backend.report.domain.model.ReportIssue;

/** ReportIssue(Report Aggregate 내부 구성요소) 조회 포트. */
public interface ReportIssueRepository {

  List<ReportIssue> findAllByReportId(UUID reportId);
}
