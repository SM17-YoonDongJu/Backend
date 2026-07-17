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

import com.soma.backend.domain.report.entity.QAdjusterReview;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.QUser;

/**
 * 내 검수 내역 목록 조회 QueryDSL 구현. 네이티브 없이(하네스 규칙) report_reviews를 reports·users와 조인해
 * 사건 정보를, adjuster_reviews와 (report_id, adjuster_id)로 LEFT JOIN해 사건별 고객 평점을 함께 가져온다.
 * status/월 필터는 rv 컬럼에만 걸리므로 count는 조인 없이 rv만으로 센다.
 */
public class ReportReviewRepositoryImpl implements ReportReviewRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  public ReportReviewRepositoryImpl(JPAQueryFactory queryFactory) {
    this.queryFactory = queryFactory;
  }

  @Override
  public Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, String status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable) {
    QReportReview rv = QReportReview.reportReview;
    QReport r = QReport.report;
    QUser u = QUser.user;
    QAdjusterReview ar = QAdjusterReview.adjusterReview;

    BooleanBuilder where = new BooleanBuilder();
    where.and(rv.adjusterId.eq(adjusterId));
    if (status != null) {
      where.and(rv.status.eq(ReviewStatus.valueOf(status)));
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
            r.caseNo,
            r.title,
            r.accidentType,
            u.region,
            rv.status,
            rv.createdAt,
            rv.estimateMinAmount,
            rv.estimateMaxAmount,
            ar.score))
        .from(rv)
        .join(r).on(r.id.eq(rv.reportId))
        .join(u).on(u.id.eq(r.userId))
        .leftJoin(ar).on(ar.reportId.eq(rv.reportId).and(ar.adjusterId.eq(rv.adjusterId)))
        .where(where)
        .orderBy(rv.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    Long total = queryFactory.select(rv.count()).from(rv).where(where).fetchOne();
    return new PageImpl<>(content, pageable, total == null ? 0L : total);
  }
}
