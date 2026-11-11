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
 * ReportReview 동적 목록 조회 QueryDSL 구현(네이티브 없이 — 하네스 규칙).
 *
 * <p>{@link #findReviewedReportRows}는 report_reviews를 reports·users와 조인해 사건 정보를,
 * adjuster_reviews와 (report_id, adjuster_id)로 LEFT JOIN해 사건별 고객 평점을 함께 가져온다.
 * {@link #findProposalRows}는 사정사(users)와 그 프로필(adjuster_profiles)을 붙여 제안 목록을 만든다.
 * 두 쿼리 모두 필터가 rv 컬럼에만 걸리므로 count는 조인 없이 rv만으로 센다.
 */
@RequiredArgsConstructor
public class ReportReviewRepositoryImpl implements ReportReviewRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, ReviewStatus status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable) {
    QReportReview rv = QReportReview.reportReview;
    QReport rp = QReport.report;
    QUser us = QUser.user;
    QAdjusterReview ar = QAdjusterReview.adjusterReview;

    BooleanBuilder where = new BooleanBuilder();
    where.and(rv.adjusterId.eq(adjusterId));
    if (status != null) {
      where.and(rv.status.eq(status));
    }
    if (monthFrom != null) {
      where.and(rv.createdAt.goe(monthFrom));
    }
    if (monthTo != null) {
      where.and(rv.createdAt.lt(monthTo));
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
}
