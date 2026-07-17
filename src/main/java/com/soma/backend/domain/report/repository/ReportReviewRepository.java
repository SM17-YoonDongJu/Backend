package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.ReportReview;

/** ReportReview Aggregate Spring Data JPA 리포지토리 + 통계·목록 조회 전용 파생 쿼리. */
public interface ReportReviewRepository extends JpaRepository<ReportReview, UUID> {

  Optional<ReportReview> findByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  /**
   * 검수 행 멱등 생성(스켈레톤). UK(report_id, adjuster_id) 충돌 시 DB가 무시하므로,
   * 같은 사정사의 최초 검수 동시 제출에도 UK 충돌 500이 나지 않는다. 이후 SELECT→UPDATE로 내용 갱신.
   */
  @Modifying
  @Query(value = "INSERT INTO report_reviews (id, report_id, adjuster_id, status, created_at) "
      + "VALUES (gen_random_uuid(), :reportId, :adjusterId, 'SENT', now()) "
      + "ON CONFLICT (report_id, adjuster_id) DO NOTHING", nativeQuery = true)
  void insertIfAbsent(@Param("reportId") UUID reportId, @Param("adjusterId") UUID adjusterId);

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

  /** 홈 "진행 중인 사건" 미리보기(top N) — 최근 작업순. Pageable로 개수를 제한한다. */
  @Query(value = "SELECT rv.report_id AS reportId, r.case_no AS caseNo, r.accident_type AS accidentType, "
      + "r.title AS title, r.status AS reportStatus, rv.status AS reviewStatus "
      + "FROM report_reviews rv "
      + "JOIN reports r ON r.id = rv.report_id "
      + "WHERE rv.adjuster_id = :adjusterId AND rv.status IN ('SENT', 'COUNSELING') "
      + "ORDER BY rv.created_at DESC",
      nativeQuery = true)
  List<InProgressCaseRow> findInProgressCases(@Param("adjusterId") UUID adjusterId, Pageable pageable);

  @Query(value = "SELECT rv.report_id AS reportId, r.case_no AS caseNo, r.title AS title, "
      + "r.accident_type AS accidentType, u.region AS region, rv.status AS status, "
      + "rv.created_at AS reviewedAt, "
      + "rv.estimate_min_amount AS confirmedMinAmount, rv.estimate_max_amount AS confirmedMaxAmount "
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

  /**
   * GET /reports/{reportId}/proposals 목록(design.md §6). REJECTED는 노출하지 않는다.
   * NOTE(backend-developer): design.md는 rating 출처로 {@code adjuster_profiles.rating_mean}을
   * 지정하지만 V1 스키마에 해당 컬럼이 없다 — {@code adjuster_reviews.score} 평균으로 대체했다(ReportRepository와 동일 이슈).
   * proposalSummary는 report_reviews.review 원문을 그대로 사용한다(별도 요약 컬럼 없음).
   */
  @Query(value = "SELECT rv.id AS proposalId, rv.adjuster_id AS adjusterId, u.nickname AS nickname, "
      + "rating.avg_score AS rating, rv.review AS proposalSummary, rv.status AS status, "
      + "rv.created_at AS submittedAt "
      + "FROM report_reviews rv "
      + "JOIN users u ON u.id = rv.adjuster_id "
      + "LEFT JOIN (SELECT adjuster_id, AVG(score) AS avg_score FROM adjuster_reviews GROUP BY adjuster_id) rating "
      + "ON rating.adjuster_id = rv.adjuster_id "
      + "WHERE rv.report_id = :reportId "
      + "AND rv.status <> 'REJECTED' "
      + "ORDER BY rv.created_at DESC",
      countQuery = "SELECT COUNT(*) FROM report_reviews rv "
          + "WHERE rv.report_id = :reportId AND rv.status <> 'REJECTED'",
      nativeQuery = true)
  Page<ProposalRow> findProposalRows(@Param("reportId") UUID reportId, Pageable pageable);
}
