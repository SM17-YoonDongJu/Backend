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

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportHold;
import com.soma.backend.domain.report.entity.QReportIssue;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
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
  public Page<ReportCardRow> findUserReportCards(UUID userId, ReportStatus status, Pageable pageable) {
    QReport rp = QReport.report;
    // ACCEPTED 제안(리포트당 최대 1건, accept 시 리포트 종결)을 붙이는 조인 별칭과, proposalCount 상관
    // 서브쿼리용 별도 별칭을 분리한다(같은 report_reviews를 서로 다른 조건으로 두 번 참조).
    QReportReview accepted = QReportReview.reportReview;
    QReportReview sibling = new QReportReview("sibling");
    QUser au = QUser.user;
    QAdjusterProfile ap = QAdjusterProfile.adjusterProfile;

    BooleanBuilder where = new BooleanBuilder();
    where.and(rp.userId.eq(userId));
    if (status != null) {
      where.and(rp.status.eq(status));
    }

    List<ReportCardRow> content = queryFactory
        .select(Projections.constructor(ReportCardRow.class,
            rp.id, rp.status, rp.accidentType, rp.title, rp.createdAt, rp.caseNo,
            rp.claimedMinAmount, rp.claimedMaxAmount,
            // proposalCount = REJECTED 제외 제안 수(SENT·COUNSELING·ACCEPTED).
            JPAExpressions.select(sibling.count()).from(sibling)
                .where(sibling.reportId.eq(rp.id).and(sibling.status.ne(ReviewStatus.REJECTED))),
            accepted.updatedAt, au.nickname, accepted.estimateMinAmount, accepted.estimateMaxAmount,
            ap.ratingMean))
        .from(rp)
        .leftJoin(accepted).on(accepted.reportId.eq(rp.id).and(accepted.status.eq(ReviewStatus.ACCEPTED)))
        .leftJoin(au).on(au.id.eq(accepted.adjusterId))
        .leftJoin(ap).on(ap.userId.eq(accepted.adjusterId))
        .where(where)
        .orderBy(rp.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    // 카운트는 소유자·status 필터만 걸린 reports 단일 테이블 집계다(ACCEPTED 조인은 행 수를 늘리지 않음).
    Long total = queryFactory
        .select(rp.count())
        .from(rp)
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

  @Override
  public CustomerReportDetailRow findCustomerReportDetail(UUID reportId) {
    QReport rp = QReport.report;
    // 채택된 제안(리포트당 최대 1건)을 report_id로, 담당 사정사(users·adjuster_profiles)를 report.adjuster_id로 붙인다.
    QReportReview accepted = QReportReview.reportReview;
    QUser adjusterUser = QUser.user;
    QAdjusterProfile adjusterProfile = QAdjusterProfile.adjusterProfile;

    return queryFactory
        .select(Projections.constructor(CustomerReportDetailRow.class,
            accepted.id, accepted.review, accepted.updatedAt,
            accepted.applicableGuarantees, accepted.omittedSpecialContract, accepted.basisTermsPrecedents,
            adjusterUser.nickname, adjusterProfile.career))
        .from(rp)
        .leftJoin(accepted).on(accepted.reportId.eq(rp.id).and(accepted.status.eq(ReviewStatus.ACCEPTED)))
        .leftJoin(adjusterUser).on(adjusterUser.id.eq(rp.adjusterId))
        .leftJoin(adjusterProfile).on(adjusterProfile.userId.eq(rp.adjusterId))
        .where(rp.id.eq(reportId))
        .fetchOne();
  }
}
