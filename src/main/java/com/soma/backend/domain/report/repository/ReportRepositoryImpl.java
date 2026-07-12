package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportHold;
import com.soma.backend.domain.report.entity.QReportIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.user.entity.QUser;

/** Report 동적 조회 QueryDSL 구현. issueCount·held는 상관 서브쿼리, users 조인은 엔티티 조인(on)으로 처리한다. */
@RequiredArgsConstructor
public class ReportRepositoryImpl implements ReportRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  @Override
  public Page<PendingReviewRow> findPendingReviewRows(
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId, Pageable pageable) {
    QReport rp = QReport.report;
    QUser us = QUser.user;
    QReportIssue ri = QReportIssue.reportIssue;
    QReportHold rh = QReportHold.reportHold;

    BooleanBuilder where = new BooleanBuilder();
    if (status != null) {
      where.and(rp.status.eq(status));
    }
    if (accidentType != null) {
      where.and(rp.accidentType.eq(accidentType));
    }
    if (region != null) {
      where.and(us.region.eq(region));
    }

    List<PendingReviewRow> content = queryFactory
        .select(Projections.constructor(PendingReviewRow.class,
            rp.id, rp.caseNo, rp.title, rp.accidentType, us.region, rp.status,
            rp.claimedMinAmount, rp.claimedMaxAmount, rp.offeredAmount,
            JPAExpressions.select(ri.count()).from(ri).where(ri.reportId.eq(rp.id)),
            JPAExpressions.selectOne().from(rh)
                .where(rh.reportId.eq(rp.id).and(rh.adjusterId.eq(adjusterId))).exists(),
            rp.createdAt))
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(where)
        .orderBy(rp.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    Long total = queryFactory
        .select(rp.count())
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(where)
        .fetchOne();

    return new PageImpl<>(content, pageable, total == null ? 0L : total);
  }
}
