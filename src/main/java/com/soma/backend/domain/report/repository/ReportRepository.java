package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.Report;

/** Report Aggregate Spring Data JPA 리포지토리 + 목록/요약 조회 전용 파생 쿼리(N+1 방지). */
public interface ReportRepository extends JpaRepository<Report, UUID> {

  List<Report> findAllByIdIn(List<UUID> ids);

  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION")
  long countPending();

  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION "
      + "AND r.createdAt <= :dueSoonThreshold")
  long countDueSoon(@Param("dueSoonThreshold") LocalDateTime dueSoonThreshold);

  @Query(value = "SELECT r.id AS reportId, r.case_no AS caseNo, r.title AS title, "
      + "r.accident_type AS accidentType, r.region AS region, r.status AS status, "
      + "r.claimed_min_amount AS claimedMinAmount, r.claimed_max_amount AS claimedMaxAmount, "
      + "r.offered_amount AS offeredAmount, "
      + "(SELECT COUNT(*) FROM report_issues ri WHERE ri.report_id = r.id) AS issueCount, "
      + "EXISTS(SELECT 1 FROM report_holds rh WHERE rh.report_id = r.id AND rh.adjuster_id = :adjusterId) AS held "
      + "FROM reports r "
      + "WHERE (:status IS NULL OR r.status = :status) "
      + "AND (:accidentType IS NULL OR r.accident_type = :accidentType) "
      + "AND (:region IS NULL OR r.region = :region) "
      + "ORDER BY r.created_at DESC",
      countQuery = "SELECT COUNT(*) FROM reports r "
          + "WHERE (:status IS NULL OR r.status = :status) "
          + "AND (:accidentType IS NULL OR r.accident_type = :accidentType) "
          + "AND (:region IS NULL OR r.region = :region)",
      nativeQuery = true)
  Page<PendingReviewRow> findPendingReviewRows(
      @Param("status") String status,
      @Param("accidentType") String accidentType,
      @Param("region") String region,
      @Param("adjusterId") UUID adjusterId,
      Pageable pageable);
}
