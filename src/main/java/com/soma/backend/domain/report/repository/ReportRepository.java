package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
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

  /*
   * 아직 엔티티로 모델링되지 않은 테이블을 조인하는 읽기 전용 projection이라 QueryDSL로 표현할 수 없다.
   * 하네스의 native query 금지 규칙에 대한 '문서화된 예외'로 유지한다(해당 도메인 모델링 시 QueryDSL로 전환):
   *   - findReviewContext : user_claims / insurance_products / insurers (미매핑)
   * diagnosis·hospitalization은 user_claims details(jsonb) 전환으로 컬럼에서 제거됨(추후 details 기반 노출).
   * region(text[])은 findRegionByReportId(QueryDSL)로 별도 조회하므로 여기서는 선택하지 않는다.
   */
  @Query(value = "SELECT u.nickname AS nickname, u.gender AS gender, u.birth_date AS birthDate, "
      + "u.created_at AS joinedAt, "
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
