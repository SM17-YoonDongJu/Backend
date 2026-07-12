package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.ReviewStatus;

/** ReportReview 동적 조회. native query 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportReviewRepositoryCustom {

  Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, ReviewStatus status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable);

  List<InProgressCaseRow> findInProgressCases(UUID adjusterId, Pageable pageable);
}
