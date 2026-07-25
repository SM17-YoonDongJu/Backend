package com.soma.backend.domain.report.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
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
    // 검수 대기 목록은 검수 단계(AWAITING_INSPECTION·AWAITING_ADOPTION, 상세 조회 노출 정책과 동일)만 노출한다.
    // CLOSED·COUNSELING·NOT_SELECTED가 목록에 새어 클릭 시 상세가 404 나던 불일치를 막는다.
    where.and(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION));
    if (status != null) {
      where.and(rp.status.eq(status));
    }
    if (accidentType != null) {
      where.and(rp.accidentType.eq(accidentType));
    }
    if (region != null) {
      // users.region이 text[]라 동등비교 대신 '배열이 필터 지역을 포함'하는지로 매칭한다(Hibernate array_contains).
      where.and(Expressions.booleanTemplate("array_contains({0}, {1})", us.region, region));
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

  @Override
  public List<String> findRegionByReportId(UUID reportId) {
    QReport rp = QReport.report;
    QUser us = QUser.user;
    return queryFactory
        .select(us.region)
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(rp.id.eq(reportId))
        .fetchOne();
  }
}
