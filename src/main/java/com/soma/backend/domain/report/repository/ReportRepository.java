package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
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

  /** 홈 검수 대기 풀 카운트 — 검수 대기(AWAITING_INSPECTION)와 채택 대기(AWAITING_ADOPTION) 합산. */
  @Query("SELECT COUNT(r) FROM Report r WHERE r.status IN ("
      + "com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION, "
      + "com.soma.backend.domain.report.entity.ReportStatus.AWAITING_ADOPTION)")
  long countPendingPool();

  /** 홈 검수 대기 풀 중 신규(threshold 이후 접수) 카운트. threshold는 서비스가 잠정 규칙으로 계산한다. */
  @Query("SELECT COUNT(r) FROM Report r WHERE r.status IN ("
      + "com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION, "
      + "com.soma.backend.domain.report.entity.ReportStatus.AWAITING_ADOPTION) "
      + "AND r.createdAt >= :newThreshold")
  long countPendingPoolNew(@Param("newThreshold") LocalDateTime newThreshold);

  /**
   * 홈 헤더·요약 카드용 사정사 비정규화 정보. name은 adjuster_profiles.name 우선(없으면 nickname),
   * 누적 검수·상담·평점은 adjuster_profiles 비정규화 컬럼에서 읽는다.
   */
  @Query(value = "SELECT COALESCE(ap.name, u.nickname) AS name, u.avatar_url AS avatarUrl, "
      + "ap.cases_reviewed AS casesReviewed, ap.completed_consult_count AS completedConsultCount, "
      + "ap.rating_mean AS ratingMean, ap.review_count AS reviewCount "
      + "FROM users u LEFT JOIN adjuster_profiles ap ON ap.user_id = u.id WHERE u.id = :userId",
      nativeQuery = true)
  AdjusterIdentityRow findAdjusterIdentity(@Param("userId") UUID userId);

  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION "
      + "AND r.createdAt <= :dueSoonThreshold")
  long countDueSoon(@Param("dueSoonThreshold") LocalDateTime dueSoonThreshold);

  @Query(value = "SELECT u.region FROM users u JOIN reports r ON r.user_id = u.id WHERE r.id = :reportId",
      nativeQuery = true)
  String findRegionByReportId(@Param("reportId") UUID reportId);

  @Query(value = "SELECT r.id AS reportId, r.case_no AS caseNo, r.title AS title, "
      + "r.accident_type AS accidentType, u.region AS region, r.status AS status, "
      + "r.created_at AS createdAt, "
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
   * API#6 검수 대기 상세 컨텍스트 조인(의뢰인·사건 입력·보험상품/보험사).
   * diagnosis·hospitalization은 user_claims details(jsonb) 전환으로 컬럼에서 제거됨(추후 details 기반 노출).
   */
  @Query(value = "SELECT u.nickname AS nickname, u.gender AS gender, u.birth_date AS birthDate, "
      + "u.region AS region, u.created_at AS joinedAt, "
      + "uc.accident_type AS claimAccidentType, uc.accident_date AS accidentDate, "
      + "uc.description AS claimDescription, "
      + "uc.additional_information AS additionalInformation, "
      + "ip.product_name AS productName, ins.name AS insurerName "
      + "FROM reports r "
      + "JOIN users u ON u.id = r.user_id "
      + "LEFT JOIN user_claims uc ON uc.id = r.claim_id "
      + "LEFT JOIN insurance_products ip ON ip.id = r.product_id "
      + "LEFT JOIN insurers ins ON ins.id = ip.insurer_id "
      + "WHERE r.id = :reportId",
      nativeQuery = true)
  ReviewContextRow findReviewContext(@Param("reportId") UUID reportId);
}
