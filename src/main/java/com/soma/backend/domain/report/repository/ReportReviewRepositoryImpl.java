package com.soma.backend.domain.report.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.report.entity.QAdjusterReview;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.QUser;

/**
 * ReportReview 동적 조회 QueryDSL 구현(네이티브 없이 — 하네스 규칙).
 *
 * <p>{@link #findReviewedReportRows}는 report_reviews를 reports·users와 조인해 사건 정보를,
 * adjuster_reviews와 (report_id, adjuster_id)로 LEFT JOIN해 사건별 고객 평점을 함께 가져온다.
 * {@link #countByStatusGrouped}는 그 목록과 같은 월 조건으로 상태별 건수를 조인 없이 집계한다.
 * {@link #findProposalRows}는 사정사(users)와 그 프로필(adjuster_profiles)을 붙여 제안 목록을 만든다.
 * 두 목록 쿼리 모두 필터가 rv 컬럼에만 걸리므로 count는 조인 없이 rv만으로 센다.
 */
@RequiredArgsConstructor
public class ReportReviewRepositoryImpl implements ReportReviewRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  /**
   * 목록 — 사정사·월 조건은 {@code reviewedByAdjusterInMonth}로 상태별 건수와 공유하고, status 탭 필터만 여기서
   * 더한다. 최신 검수순으로 정렬해 페이지로 자른다.
   */
  @Override
  public Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, ReviewStatus status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable) {
    QReportReview rv = QReportReview.reportReview;
    QReport rp = QReport.report;
    QUser us = QUser.user;
    QAdjusterReview ar = QAdjusterReview.adjusterReview;

    BooleanBuilder where = reviewedByAdjusterInMonth(rv, adjusterId, monthFrom, monthTo);
    if (status != null) {
      where.and(rv.status.eq(status));
    }

    List<ReviewedReportRow> content = queryFactory
        .select(Projections.constructor(ReviewedReportRow.class,
            rv.reportId,
            rp.caseNo,
            rp.title,
            rp.accidentType,
            us.region,
            rv.status,
            rv.createdAt,
            rv.estimateMinAmount,
            rv.estimateMaxAmount,
            ar.score))
        .from(rv)
        .join(rp).on(rp.id.eq(rv.reportId))
        .join(us).on(us.id.eq(rp.userId))
        .leftJoin(ar).on(ar.reportId.eq(rv.reportId).and(ar.adjusterId.eq(rv.adjusterId)))
        .where(where)
        .orderBy(rv.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    Long total = queryFactory
        .select(rv.count())
        .from(rv)
        .where(where)
        .fetchOne();

    return new PageImpl<>(content, pageable, total == null ? 0L : total);
  }

  /**
   * 상태별 건수 — 목록과 같은 사정사·월 조건으로 status를 그룹 집계한다. status 탭 필터는 걸지 않는다. 조인이 없고,
   * 행 순서는 의미가 없다(서비스가 total → enum 순으로 미리 만든 맵에 덮어쓴다).
   */
  @Override
  public List<StatusCount> countByStatusGrouped(UUID adjusterId, LocalDateTime monthFrom, LocalDateTime monthTo) {
    QReportReview rv = QReportReview.reportReview;
    return queryFactory
        .select(Projections.constructor(StatusCount.class, rv.status, rv.count()))
        .from(rv)
        .where(reviewedByAdjusterInMonth(rv, adjusterId, monthFrom, monthTo))
        .groupBy(rv.status)
        .fetch();
  }

  /**
   * 제안 목록 — rating은 adjuster_profiles.rating_mean(scale 2)을 그대로 읽는다. 프로필 행이 없거나(LEFT JOIN)
   * 평가가 0건이면 null이다. 예전에는 adjuster_reviews를 AVG로 직접 집계했는데(native 예외), 이슈 #304로
   * rating_mean에 재집계 write 경로가 생기고 V46이 기존 데이터를 백필하면서 조인 한 번으로 대체됐다.
   * proposalSummary는 report_reviews.review 원문을 그대로 쓴다(별도 요약 컬럼 없음).
   */
  @Override
  public Page<ProposalRow> findProposalRows(UUID reportId, Pageable pageable) {
    QReportReview rv = QReportReview.reportReview;
    QUser us = QUser.user;
    QAdjusterProfile ap = QAdjusterProfile.adjusterProfile;

    BooleanBuilder where = new BooleanBuilder();
    where.and(rv.reportId.eq(reportId));
    where.and(rv.status.ne(ReviewStatus.REJECTED));

    List<ProposalRow> content = queryFactory
        .select(Projections.constructor(ProposalRow.class,
            rv.id,
            rv.adjusterId,
            us.nickname,
            ap.ratingMean,
            rv.review,
            rv.status,
            rv.createdAt))
        .from(rv)
        .join(us).on(us.id.eq(rv.adjusterId))
        .leftJoin(ap).on(ap.userId.eq(rv.adjusterId))
        .where(where)
        .orderBy(rv.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    Long total = queryFactory
        .select(rv.count())
        .from(rv)
        .where(where)
        .fetchOne();

    return new PageImpl<>(content, pageable, total == null ? 0L : total);
  }

  /**
   * 요청 사정사 본인 검수 중 [monthFrom, monthTo)에 등록된 것. 경계는 값이 있을 때만 붙인다 — 예전 JPQL의
   * {@code (:monthFrom IS NULL OR ...)}는 PgJDBC가 timestamp 파라미터를 타입 미지정으로 보내 PostgreSQL이
   * {@code IS NULL} 쪽 타입을 정하지 못해(42P18) 값 유무와 관계없이 실패했다(#411). 목록과 상태별 건수가 이
   * 정의를 공유해 두 화면 요소의 월 필터가 어긋나지 않는다.
   */
  private static BooleanBuilder reviewedByAdjusterInMonth(
      QReportReview rv, UUID adjusterId, LocalDateTime monthFrom, LocalDateTime monthTo) {
    BooleanBuilder where = new BooleanBuilder();
    where.and(rv.adjusterId.eq(adjusterId));
    if (monthFrom != null) {
      where.and(rv.createdAt.goe(monthFrom));
    }
    if (monthTo != null) {
      where.and(rv.createdAt.lt(monthTo));
    }
    return where;
  }
}
