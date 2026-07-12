package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.soma.backend.domain.report.entity.Report;

/**
 * Report Aggregate Spring Data JPA 리포지토리 + 요약/카운트 조회.
 * 동적 목록 조회는 {@link ReportRepositoryCustom}(QueryDSL)에서 구현한다.
 */
public interface ReportRepository extends JpaRepository<Report, UUID>, ReportRepositoryCustom {

  List<Report> findAllByIdIn(List<UUID> ids);

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

  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION "
      + "AND r.createdAt <= :dueSoonThreshold")
  long countDueSoon(@Param("dueSoonThreshold") LocalDateTime dueSoonThreshold);

  /** 리포트 의뢰인의 지역(users.region). 매핑된 엔티티 간 조인이라 JPQL 스칼라 조회로 충분하다. */
  @Query("SELECT u.region FROM User u, Report r WHERE u.id = r.userId AND r.id = :reportId")
  String findRegionByReportId(@Param("reportId") UUID reportId);

  /*
   * 아래 2건은 아직 엔티티로 모델링되지 않은 테이블을 조인하는 읽기 전용 projection이라 QueryDSL로 표현할 수 없다.
   * 하네스의 native query 금지 규칙에 대한 '문서화된 예외'로 유지한다(해당 도메인 모델링 시 QueryDSL로 전환):
   *   - findAdjusterIdentity : adjuster_profiles (미매핑)
   *   - findReviewContext    : user_claims / insurance_products / insurers (미매핑)
   */
  @Query(value = "SELECT COALESCE(ap.name, u.nickname) AS name, u.avatar_url AS avatarUrl, "
      + "ap.cases_reviewed AS casesReviewed, ap.completed_consult_count AS completedConsultCount, "
      + "ap.rating_mean AS ratingMean, ap.review_count AS reviewCount "
      + "FROM users u LEFT JOIN adjuster_profiles ap ON ap.user_id = u.id WHERE u.id = :userId",
      nativeQuery = true)
  AdjusterIdentityRow findAdjusterIdentity(@Param("userId") UUID userId);

  @Query(value = "SELECT u.nickname AS nickname, u.gender AS gender, u.birth_date AS birthDate, "
      + "u.region AS region, u.created_at AS joinedAt, "
      + "uc.accident_type AS claimAccidentType, uc.diagnosis AS diagnosis, uc.accident_date AS accidentDate, "
      + "CAST(uc.hospitalization AS text) AS hospitalization, uc.description AS claimDescription, "
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
