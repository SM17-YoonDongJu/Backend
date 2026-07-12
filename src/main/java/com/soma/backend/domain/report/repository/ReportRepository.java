package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

  /**
   * 당일 case_no 시퀀스를 원자적으로 발급한다(1부터). ON CONFLICT DO UPDATE로 동시 요청에도 단일 행이
   * 원자 증가하므로, count-then-insert 경쟁으로 인한 case_no UNIQUE 위반(→500)을 원천 차단한다(ReportHold 관례).
   */
  @Query(value = "INSERT INTO report_case_sequences (day, seq) VALUES (:day, 1) "
      + "ON CONFLICT (day) DO UPDATE SET seq = report_case_sequences.seq + 1 "
      + "RETURNING seq", nativeQuery = true)
  int nextCaseNoSequence(@Param("day") LocalDate day);

  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION")
  long countPending();

  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION "
      + "AND r.createdAt <= :dueSoonThreshold")
  long countDueSoon(@Param("dueSoonThreshold") LocalDateTime dueSoonThreshold);

  @Query(value = "SELECT r.id AS reportId, r.case_no AS caseNo, r.title AS title, "
      + "r.accident_type AS accidentType, u.region AS region, r.status AS status, "
      + "r.claimed_min_amount AS claimedMinAmount, r.claimed_max_amount AS claimedMaxAmount, "
      + "r.offered_amount AS offeredAmount, "
      + "(SELECT COUNT(*) FROM report_issues ri WHERE ri.report_id = r.id) AS issueCount, "
      + "EXISTS(SELECT 1 FROM report_holds rh WHERE rh.report_id = r.id AND rh.adjuster_id = :adjusterId) AS held "
      + "FROM reports r "
      + "JOIN users u ON u.id = r.user_id "
      + "WHERE (:status IS NULL OR r.status = :status) "
      + "AND (:accidentType IS NULL OR r.accident_type = :accidentType) "
      + "AND (:region IS NULL OR u.region = :region) "
      + "ORDER BY r.created_at DESC",
      countQuery = "SELECT COUNT(*) FROM reports r "
          + "JOIN users u ON u.id = r.user_id "
          + "WHERE (:status IS NULL OR r.status = :status) "
          + "AND (:accidentType IS NULL OR r.accident_type = :accidentType) "
          + "AND (:region IS NULL OR u.region = :region)",
      nativeQuery = true)
  Page<PendingReviewRow> findPendingReviewRows(
      @Param("status") String status,
      @Param("accidentType") String accidentType,
      @Param("region") String region,
      @Param("adjusterId") UUID adjusterId,
      Pageable pageable);

  /**
   * GET /reports 유저 대시보드 카드 목록(design.md §6). 채택된 제안(report_reviews.status='ACCEPTED')을
   * LEFT JOIN해 확정 사정사·확정 견적을 붙인다. rating은 {@code adjuster_profiles.rating_mean}
   * 비정규화 컬럼을 직접 읽는다(V4 백필 + 후기 write 시 재집계) — 매 조회마다 adjuster_reviews를
   * 전체 GROUP BY 하던 파생 테이블 조인을 제거해 병목을 없앴다.
   */
  @Query(value = "SELECT r.id AS reportId, r.status AS status, r.accident_type AS accidentType, "
      + "r.title AS title, r.created_at AS createdAt, r.case_no AS caseNo, "
      + "r.claimed_min_amount AS claimedMinAmount, r.claimed_max_amount AS claimedMaxAmount, "
      + "(SELECT COUNT(*) FROM report_reviews rv2 WHERE rv2.report_id = r.id) AS proposalCount, "
      + "accepted.updated_at AS reviewedAt, "
      + "au.nickname AS adjusterNickname, "
      + "accepted.estimate_min_amount AS confirmedMinAmount, "
      + "accepted.estimate_max_amount AS confirmedMaxAmount, "
      + "ap.rating_mean AS rating "
      + "FROM reports r "
      + "LEFT JOIN report_reviews accepted ON accepted.report_id = r.id AND accepted.status = 'ACCEPTED' "
      + "LEFT JOIN users au ON au.id = accepted.adjuster_id "
      + "LEFT JOIN adjuster_profiles ap ON ap.user_id = accepted.adjuster_id "
      + "WHERE r.user_id = :userId "
      + "AND (:status IS NULL OR r.status = :status) "
      + "ORDER BY r.created_at DESC",
      countQuery = "SELECT COUNT(*) FROM reports r "
          + "WHERE r.user_id = :userId "
          + "AND (:status IS NULL OR r.status = :status)",
      nativeQuery = true)
  Page<ReportCardRow> findUserReportCards(
      @Param("userId") UUID userId, @Param("status") String status, Pageable pageable);

  /** GET /reports/{reportId} 상세 조회 보조 조인(요청자·채택 사정사 닉네임, 보험상품/보험사명). */
  @Query(value = "SELECT u.nickname AS clientNickname, u.gender AS clientGender, u.region AS clientRegion, "
      + "au.nickname AS adjusterNickname, ip.product_name AS productName, ip.category AS productCategory, "
      + "ins.name AS insurerName "
      + "FROM reports r "
      + "JOIN users u ON u.id = r.user_id "
      + "LEFT JOIN users au ON au.id = r.adjuster_id "
      + "LEFT JOIN insurance_products ip ON ip.id = r.product_id "
      + "LEFT JOIN insurers ins ON ins.id = ip.insurer_id "
      + "WHERE r.id = :reportId",
      nativeQuery = true)
  Optional<ReportDetailRow> findDetailRow(@Param("reportId") UUID reportId);
}
