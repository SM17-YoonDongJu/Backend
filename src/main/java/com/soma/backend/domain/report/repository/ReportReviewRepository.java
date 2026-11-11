package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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
   * 상담 완료(담당 확정) 수 재집계(adjuster_profiles.completed_consult_count) — 사용자가 최종 채택한
   * 제안(ACCEPTED) 건수다. "검수를 등록한 건수"가 아니다.
   *
   * <p>{@link #countReachedConsultationByAdjusterId}(COUNSELING+ACCEPTED)를 재사용하지 않는다 — 그건
   * "상담 도달"(전환율 분자)이고 여기는 "담당 확정"이라 모수가 다르다.
   */
  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status = com.soma.backend.domain.report.entity.ReviewStatus.ACCEPTED")
  long countAcceptedByAdjusterId(@Param("adjusterId") UUID adjusterId);

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

  /**
   * 마이페이지 누적 상담전환(전환율 분자) — 상담 도달 기준 status IN (COUNSELING, ACCEPTED).
   * COUNSELING은 전이형 상태라 사용자 수락 시 ACCEPTED로 넘어가므로, 과거 전환분 누락을 막기 위해 ACCEPTED를
   * 포함한다(COUNSELING-only인 {@link #countConsultationConvertedByAdjusterId}와 공존).
   */
  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status IN ("
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.ACCEPTED)")
  long countReachedConsultationByAdjusterId(@Param("adjusterId") UUID adjusterId);

  /**
   * 마이페이지 당월 상담전환 — 상태 전이 시각 컬럼이 없어 검수 등록 시각(created_at) 기준으로 근사한다.
   * 범위는 [from, to). 도달 기준은 {@link #countReachedConsultationByAdjusterId}와 동일하다.
   */
  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status IN ("
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.ACCEPTED) "
      + "AND rv.createdAt >= :from AND rv.createdAt < :to")
  long countReachedConsultationByAdjusterIdBetween(
      @Param("adjusterId") UUID adjusterId,
      @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

  /** 검수 대기 요약의 "진행 중인 사건" 카운트 — 요청 사정사의 미완료 검수(SENT·COUNSELING). */
  @Query("SELECT COUNT(rv) FROM ReportReview rv WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status IN ("
      + "com.soma.backend.domain.report.entity.ReviewStatus.SENT, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING)")
  long countInProgressByAdjusterId(@Param("adjusterId") UUID adjusterId);

  /**
   * 평가 자격 확인 — 이 사정사가 요청 사용자(리포트 소유자)의 사건을 수임(ACCEPTED)한 이력의 report_id(최신순).
   * 결과가 있으면 평가 자격이 있고, 첫 값을 평가와 연결할 사건으로 쓴다. 소유자 필터는 서브쿼리로 건다.
   */
  @Query("SELECT rv.reportId FROM ReportReview rv "
      + "WHERE rv.adjusterId = :adjusterId "
      + "AND rv.status = com.soma.backend.domain.report.entity.ReviewStatus.ACCEPTED "
      + "AND rv.reportId IN (SELECT r.id FROM Report r WHERE r.userId = :userId) "
      + "ORDER BY rv.createdAt DESC")
  List<UUID> findAcceptedReportIdsForReviewer(
      @Param("adjusterId") UUID adjusterId, @Param("userId") UUID userId);

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
}
