package com.soma.backend.report.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.model.ReportReviewIssue;
import com.soma.backend.report.domain.repository.ReportReviewIssueRepository;

/** ReportReviewIssueRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportReviewIssueRepositoryImpl implements ReportReviewIssueRepository {

  private final ReportReviewIssueJpaRepository reportReviewIssueJpaRepository;

  @Override
  public List<ReportReviewIssue> saveAll(List<ReportReviewIssue> issues) {
    return reportReviewIssueJpaRepository.saveAll(issues);
  }

  @Override
  public void deleteAllByReportReviewId(UUID reportReviewId) {
    reportReviewIssueJpaRepository.deleteAllByReportReviewId(reportReviewId);
  }
}
