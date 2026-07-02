package com.soma.backend.report.domain.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** 내 검수 내역·통계 조회 전용 포트(CQRS, API#5). */
public interface ReportReviewQueryRepository {

  long countByAdjusterId(UUID adjusterId);

  long countByAdjusterIdAndCreatedAtBetween(UUID adjusterId, LocalDateTime from, LocalDateTime to);

  long countConsultationConvertedByAdjusterId(UUID adjusterId);

  Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, String outcome, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable);
}
