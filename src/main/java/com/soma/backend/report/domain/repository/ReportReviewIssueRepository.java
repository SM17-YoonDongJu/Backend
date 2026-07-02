package com.soma.backend.report.domain.repository;

import java.util.List;
import java.util.UUID;

import com.soma.backend.report.domain.model.ReportReviewIssue;

/** ReportReviewIssue(ReportReview Aggregate 하위) 저장소 포트. */
public interface ReportReviewIssueRepository {

  List<ReportReviewIssue> saveAll(List<ReportReviewIssue> issues);

  void deleteAllByReportReviewId(UUID reportReviewId);
}
