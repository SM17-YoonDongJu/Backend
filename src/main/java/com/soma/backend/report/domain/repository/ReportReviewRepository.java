package com.soma.backend.report.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.soma.backend.report.domain.model.ReportReview;

/** ReportReview Aggregate 저장소 포트. */
public interface ReportReviewRepository {

  Optional<ReportReview> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  ReportReview save(ReportReview review);
}
