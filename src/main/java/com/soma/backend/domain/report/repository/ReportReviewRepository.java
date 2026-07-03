package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.ReportReview;

/** ReportReview Aggregate Spring Data JPA 리포지토리 + 통계·목록 조회 전용 파생 쿼리. */
public interface ReportReviewRepository extends JpaRepository<ReportReview, UUID> {

  Optional<ReportReview> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId")
  long countByAdjusterId(@Param("adjusterId") UUID adjusterId);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.createdAt >= :from AND rv.createdAt < :to")
  long countByAdjusterIdAndCreatedAtBetween(
      @Param("adjusterId") UUID adjusterId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status = com.soma.backend.domain.report.entity.ReviewStatus.CONSULTATION")
  long countConsultationConvertedByAdjusterId(@Param("adjusterId") UUID adjusterId);

  @Query(value = "SELECT rv.report_id AS reportId, r.case_no AS caseNo, r.title AS title, "
      + "r.accident_type AS accidentType, u.region AS region, rv.status AS status, "
      + "rv.created_at AS reviewedAt "
      + "FROM report_reviews rv "
      + "JOIN reports r ON r.id = rv.report_id "
      + "JOIN users u ON u.id = r.user_id "
      + "WHERE rv.adjuster_id = :adjusterId "
      + "AND (:status IS NULL OR rv.status = :status) "
      + "AND (CAST(:monthFrom AS timestamp) IS NULL OR rv.created_at >= :monthFrom) "
      + "AND (CAST(:monthTo AS timestamp) IS NULL OR rv.created_at < :monthTo) "
      + "ORDER BY rv.created_at DESC",
      countQuery = "SELECT COUNT(*) FROM report_reviews rv "
          + "WHERE rv.adjuster_id = :adjusterId "
          + "AND (:status IS NULL OR rv.status = :status) "
          + "AND (CAST(:monthFrom AS timestamp) IS NULL OR rv.created_at >= :monthFrom) "
          + "AND (CAST(:monthTo AS timestamp) IS NULL OR rv.created_at < :monthTo)",
      nativeQuery = true)
  Page<ReviewedReportRow> findReviewedReportRows(
      @Param("adjusterId") UUID adjusterId,
      @Param("status") String status,
      @Param("monthFrom") LocalDateTime monthFrom,
      @Param("monthTo") LocalDateTime monthTo,
      Pageable pageable);
}
