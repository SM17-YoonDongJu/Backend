package com.soma.backend.report.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.report.domain.model.ReportReviewIssue;

/** ReportReviewIssue(ReportReview Aggregate 하위) Spring Data JPA 리포지토리. */
public interface ReportReviewIssueJpaRepository extends JpaRepository<ReportReviewIssue, UUID> {

  List<ReportReviewIssue> findAllByReportReviewId(UUID reportReviewId);

  void deleteAllByReportReviewId(UUID reportReviewId);
}
