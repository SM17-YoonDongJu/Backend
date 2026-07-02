package com.soma.backend.report.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.model.ReportReview;
import com.soma.backend.report.domain.repository.ReportReviewRepository;

/** ReportReviewRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportReviewRepositoryImpl implements ReportReviewRepository {

  private final ReportReviewJpaRepository reportReviewJpaRepository;

  @Override
  public Optional<ReportReview> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId) {
    return reportReviewJpaRepository.findByReportIdAndAdjusterId(reportId, adjusterId);
  }

  @Override
  public ReportReview save(ReportReview review) {
    return reportReviewJpaRepository.save(review);
  }
}
