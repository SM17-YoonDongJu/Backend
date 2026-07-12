package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.ReportReview;

/**
 * ReportReview Aggregate Spring Data JPA 리포지토리 + 통계 파생 쿼리.
 * 동적 목록 조회는 {@link ReportReviewRepositoryCustom}(QueryDSL)에서 구현한다.
 */
public interface ReportReviewRepository extends JpaRepository<ReportReview, UUID>, ReportReviewRepositoryCustom {

  Optional<ReportReview> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  boolean existsByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId")
  long countByAdjusterId(@Param("adjusterId") UUID adjusterId);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.createdAt >= :from AND rv.createdAt < :to")
  long countByAdjusterIdAndCreatedAtBetween(
      @Param("adjusterId") UUID adjusterId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status = com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING")
  long countConsultationConvertedByAdjusterId(@Param("adjusterId") UUID adjusterId);

  /** 홈 "진행 중인 사건" 카운트 — 요청 사정사의 미완료 검수(SENT·COUNSELING). */
  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status IN ("
      + "com.soma.backend.domain.report.entity.ReviewStatus.SENT, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING)")
  long countInProgressByAdjusterId(@Param("adjusterId") UUID adjusterId);
}
