package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.soma.backend.domain.report.entity.ReviewStatus;

/** ReportReview 동적 조회(QueryDSL) 프래그먼트 — 네이티브 대신 QueryDSL로 작성한다(하네스 규칙). */
public interface ReportReviewRepositoryCustom {

  /**
   * API#5 내 검수 내역 목록. 사건별 확정 금액(estimate_min/max)·고객 평점(adjuster_reviews.score, 미작성 null)을
   * 함께 조회한다. status/월 필터, 최근순 정렬, 페이지네이션.
   */
  Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, ReviewStatus status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable);
}
