package com.soma.backend.domain.report.repository;

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

  /** case_no 당일 시퀀스 계산용(prefix = yyyyMMdd-). */
  long countByCaseNoStartingWith(String prefix);

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
   * LEFT JOIN해 확정 사정사·확정 견적을 붙인다.
   * NOTE(backend-developer): design.md는 rating 출처로 {@code adjuster_profiles.rating_mean}을
   * 지정하지만 V1 스키마(adjuster_profiles)에 해당 컬럼이 없다 — {@code adjuster_reviews.score}의
   * 평균으로 대체했다. 리더 확인 후 실제 컬럼/집계 방식으로 교체 필요.
   */
  @Query(value = "SELECT r.id AS reportId, r.status AS status, r.accident_type AS accidentType, "
      + "r.title AS title, r.created_at AS createdAt, r.case_no AS caseNo, "
      + "r.claimed_min_amount AS claimedMinAmount, r.claimed_max_amount AS claimedMaxAmount, "
      + "(SELECT COUNT(*) FROM report_reviews rv2 WHERE rv2.report_id = r.id) AS proposalCount, "
      + "accepted.updated_at AS reviewedAt, "
      + "au.nickname AS adjusterNickname, "
      + "accepted.estimate_min_amount AS confirmedMinAmount, "
      + "accepted.estimate_max_amount AS confirmedMaxAmount, "
      + "rating.avg_score AS rating "
      + "FROM reports r "
      + "LEFT JOIN report_reviews accepted ON accepted.report_id = r.id AND accepted.status = 'ACCEPTED' "
      + "LEFT JOIN users au ON au.id = accepted.adjuster_id "
      + "LEFT JOIN (SELECT adjuster_id, AVG(score) AS avg_score FROM adjuster_reviews GROUP BY adjuster_id) rating "
      + "ON rating.adjuster_id = accepted.adjuster_id "
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
