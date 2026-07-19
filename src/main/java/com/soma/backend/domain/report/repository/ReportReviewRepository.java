package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  /** 같은 리포트의 형제 제안(다른 사정사) 조회 — 채팅 상담 수락 시 형제 제안 일괄 거절에 사용(chat 도메인). */
  List<ReportReview> findByReportId(UUID reportId);

  boolean existsByReportIdAndAdjusterId(UUID reportId, UUID adjusterId);

  /**
   * pending-review 목록 항목에 붙일, 요청 사정사 본인의 리포트별 검수 상태. 페이지의 report_id 묶음으로
   * 한 번에 조회한다(N+1 방지). 본인 검수가 없는 리포트는 결과에 없다(→ 응답에서 null 처리).
   */
  @Query("SELECT new com.soma.backend.domain.report.repository.ReportReviewStatusRow(rv.reportId, rv.status) "
      + "FROM ReportReview rv WHERE rv.adjusterId = :adjusterId AND rv.reportId IN :reportIds")
  List<ReportReviewStatusRow> findAdjusterReviewStatuses(
      @Param("adjusterId") UUID adjusterId, @Param("reportIds") Collection<UUID> reportIds);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId")
  long countByAdjusterId(@Param("adjusterId") UUID adjusterId);

  /**
   * 마이페이지 활동 집계 — 요청 사용자(리포트 소유자)가 받은 제안 총수. 거절(REJECTED) 포함(받은 제안 누적).
   * ReportReview는 Report를 객체가 아니라 report_id로 참조하므로 소유자 필터는 서브쿼리로 건다.
   */
  @Query("SELECT COUNT(rv) FROM ReportReview rv "
      + "WHERE rv.reportId IN (SELECT r.id FROM Report r WHERE r.userId = :userId)")
  long countProposalsByReportOwner(@Param("userId") UUID userId);

  /** 마이페이지 활동 집계 — 요청 사용자의 리포트에 달린 제안 중 상담 전환(COUNSELING) 수. */
  @Query("SELECT COUNT(rv) FROM ReportReview rv "
      + "WHERE rv.status = com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING "
      + "AND rv.reportId IN (SELECT r.id FROM Report r WHERE r.userId = :userId)")
  long countConsultsByReportOwner(@Param("userId") UUID userId);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.createdAt >= :from AND rv.createdAt < :to")
  long countByAdjusterIdAndCreatedAtBetween(
      @Param("adjusterId") UUID adjusterId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status = com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING")
  long countConsultationConvertedByAdjusterId(@Param("adjusterId") UUID adjusterId);

  /** 검수 대기 요약의 "진행 중인 사건" 카운트 — 요청 사정사의 미완료 검수(SENT·COUNSELING). */
  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status IN ("
      + "com.soma.backend.domain.report.entity.ReviewStatus.SENT, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING)")
  long countInProgressByAdjusterId(@Param("adjusterId") UUID adjusterId);

  /**
   * API#5 필터 탭 배지용 — 요청 사정사 본인 검수의 상태별 건수. 월 필터는 목록과 동일하게 적용하되
   * status 탭 필터는 걸지 않는다(어떤 탭이 선택돼도 전체 분포 배지를 보여야 하므로). JPQL 생성자 표현식으로
   * StatusCount(status, count)를 만든다(네이티브 미사용).
   */
  @Query("SELECT new com.soma.backend.domain.report.repository.StatusCount(rv.status, COUNT(rv)) "
      + "FROM ReportReview rv "
      + "WHERE rv.adjusterId = :adjusterId "
      + "AND (:monthFrom IS NULL OR rv.createdAt >= :monthFrom) "
      + "AND (:monthTo IS NULL OR rv.createdAt < :monthTo) "
      + "GROUP BY rv.status")
  List<StatusCount> countByStatusGrouped(
      @Param("adjusterId") UUID adjusterId,
      @Param("monthFrom") LocalDateTime monthFrom,
      @Param("monthTo") LocalDateTime monthTo);

  /**
   * GET /reports/{reportId}/proposals 목록(design.md §6). REJECTED는 노출하지 않는다.
   * <p>native query '문서화된 예외': 아직 엔티티로 매핑되지 않은 {@code adjuster_reviews}를 조인해 사정사별
   * 평점 평균(AVG)을 상관 서브쿼리로 계산하므로 QueryDSL로 표현할 수 없다(adjuster_reviews 도메인 모델링 시 전환).
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
