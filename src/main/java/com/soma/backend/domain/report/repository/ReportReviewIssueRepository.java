package com.soma.backend.domain.report.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import com.soma.backend.domain.report.entity.ReportReviewIssue;

/** ReportReviewIssue(ReportReview Aggregate 하위) Spring Data JPA 리포지토리. */
public interface ReportReviewIssueRepository extends JpaRepository<ReportReviewIssue, UUID> {

  @Modifying
  void deleteAllByReportReviewId(UUID reportReviewId);
}
