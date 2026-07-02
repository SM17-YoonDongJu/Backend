package com.soma.backend.report.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

import com.soma.backend.report.domain.repository.ReportReviewQueryRepository;
import com.soma.backend.report.domain.repository.ReviewedReportRow;

/** ReportReviewQueryRepository 포트의 JPA 어댑터. */
@Repository
@RequiredArgsConstructor
public class ReportReviewQueryRepositoryImpl implements ReportReviewQueryRepository {

  private final ReportReviewJpaRepository reportReviewJpaRepository;

  @Override
  public long countByAdjusterId(UUID adjusterId) {
    return reportReviewJpaRepository.countByAdjusterId(adjusterId);
  }

  @Override
  public long countByAdjusterIdAndCreatedAtBetween(UUID adjusterId, LocalDateTime from, LocalDateTime to) {
    return reportReviewJpaRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, from, to);
  }

  @Override
  public long countConsultationConvertedByAdjusterId(UUID adjusterId) {
    return reportReviewJpaRepository.countConsultationConvertedByAdjusterId(adjusterId);
  }

  @Override
  public Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, String outcome, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable) {
    return reportReviewJpaRepository.findReviewedReportRows(adjusterId, outcome, monthFrom, monthTo, pageable);
  }
}
