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

import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.QUser;

/** ReportReview 동적 조회 QueryDSL 구현. Aggregate 미매핑 연관은 엔티티 조인(on)으로 처리한다. */
@RequiredArgsConstructor
public class ReportReviewRepositoryImpl implements ReportReviewRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public Page<ReviewedReportRow> findReviewedReportRows(
      UUID adjusterId, ReviewStatus status, LocalDateTime monthFrom, LocalDateTime monthTo, Pageable pageable) {
    QReportReview rv = QReportReview.reportReview;
    QReport rp = QReport.report;
    QUser us = QUser.user;

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
            rv.reportId, rp.caseNo, rp.title, rp.accidentType, us.region, rv.status, rv.createdAt))
        .from(rv)
        .join(rp).on(rp.id.eq(rv.reportId))
        .join(us).on(us.id.eq(rp.userId))
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

  @Override
  public List<InProgressCaseRow> findInProgressCases(UUID adjusterId, Pageable pageable) {
    QReportReview rv = QReportReview.reportReview;
    QReport rp = QReport.report;

    return queryFactory
        .select(Projections.constructor(InProgressCaseRow.class,
            rv.reportId, rp.caseNo, rp.accidentType, rp.title, rp.status, rv.status))
        .from(rv)
        .join(rp).on(rp.id.eq(rv.reportId))
        .where(rv.adjusterId.eq(adjusterId)
            .and(rv.status.in(ReviewStatus.SENT, ReviewStatus.COUNSELING)))
        .orderBy(rv.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
  }
}
