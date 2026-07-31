package com.soma.backend.domain.report.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
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
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId,
      Set<AccidentType> specialtyTypes, Pageable pageable) {
    QReport rp = QReport.report;
    QUser us = QUser.user;
    QReportIssue ri = QReportIssue.reportIssue;
    QReportHold rh = QReportHold.reportHold;

    BooleanBuilder where = new BooleanBuilder();
    // 검수 대기 목록은 검수 단계(AWAITING_INSPECTION·AWAITING_ADOPTION, 상세 조회 노출 정책과 동일)만 노출한다.
    // CLOSED·COUNSELING·NOT_SELECTED가 목록에 새어 클릭 시 상세가 404 나던 불일치를 막는다.
    where.and(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION));
    // 요청 사정사가 보류(report_holds)한 리포트는 목록에서 숨긴다(보류 = 내 대기열에서 제외). count 쿼리도 같은 where라 함께 줄어든다.
    where.and(JPAExpressions.selectOne().from(rh)
        .where(rh.reportId.eq(rp.id).and(rh.adjusterId.eq(adjusterId))).notExists());
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
            // 보류 건은 위 where에서 제외되므로 held는 항상 false다. FE 계약 유지를 위해 필드만 남긴다.
            Expressions.constant(false),
            rp.createdAt))
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(where)
        .orderBy(pendingReviewOrder(rp, specialtyTypes))
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

  /**
   * 검수대기 정렬: 사정사 전문분야 매칭(specialtyTypes에 든 accident_type)을 상단에 올리고, 그 안에서는 접수
   * 최신순(created_at desc)이다. 매칭 대상이 없으면 전문분야 정렬을 건너뛰고 접수 최신순만 적용한다.
   */
  private OrderSpecifier<?>[] pendingReviewOrder(QReport rp, Set<AccidentType> specialtyTypes) {
    List<OrderSpecifier<?>> orders = new ArrayList<>();
    if (specialtyTypes != null && !specialtyTypes.isEmpty()) {
      NumberExpression<Integer> specialtyRank = new CaseBuilder()
          .when(rp.accidentType.in(specialtyTypes)).then(0).otherwise(1);
      orders.add(specialtyRank.asc());
    }
    orders.add(rp.createdAt.desc());
    return orders.toArray(new OrderSpecifier[0]);
  }

  @Override
  public Page<ReportCardRow> findUserReportCards(UUID userId, ReportStatus status, Pageable pageable) {
    return queryReportCards(userId, status, false, pageable);
  }

  @Override
  public Page<ReportCardRow> findReportsWithProposals(UUID userId, Pageable pageable) {
    return queryReportCards(userId, null, true, pageable);
  }

  /**
   * 고객 리포트 카드 목록 공통 조회 — per-review. 소유자(rp.userId) 리포트에 달린 report_reviews를 1건당 1행으로
   * 편다(리포트당 리뷰 N개면 N행). {@code excludeRejected}면 REJECTED 리뷰 행을 제외한다(받은 제안 목록용).
   * status가 있으면 리포트 상태로 필터한다. reviewedAt·adjusterNickname은 그 리뷰값, proposalCount는 REJECTED
   * 제외 리뷰 수(리포트 단위 상관 서브쿼리), 나머지(accidentType·claimed·offered·treatment)는 리포트값이다.
   */
  private Page<ReportCardRow> queryReportCards(
      UUID userId, ReportStatus status, boolean excludeRejected, Pageable pageable) {
    QReport rp = QReport.report;
    QReportReview rv = QReportReview.reportReview;
    QReportReview sibling = new QReportReview("sibling");
    QUser au = QUser.user;

    BooleanBuilder where = new BooleanBuilder();
    where.and(rp.userId.eq(userId));
    if (status != null) {
      where.and(rp.status.eq(status));
    }
    if (excludeRejected) {
      where.and(rv.status.ne(ReviewStatus.REJECTED));
    }

    List<ReportCardRow> content = queryFactory
        .select(Projections.constructor(ReportCardRow.class,
            rp.id, rp.status, rp.accidentType, rp.title, rp.createdAt, rp.caseNo,
            rp.claimedMinAmount, rp.claimedMaxAmount,
            // proposalCount = REJECTED 제외 리뷰 수(리포트 단위).
            JPAExpressions.select(sibling.count()).from(sibling)
                .where(sibling.reportId.eq(rp.id).and(sibling.status.ne(ReviewStatus.REJECTED))),
            rv.updatedAt, au.nickname, rp.offeredAmount, rp.treatment))
        .from(rv)
        .join(rp).on(rp.id.eq(rv.reportId))
        .leftJoin(au).on(au.id.eq(rv.adjusterId))
        .where(where)
        .orderBy(rp.createdAt.desc(), rv.createdAt.desc())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    // 카운트는 동일 join/where 아래 리뷰 행 수(per-review 페이지네이션).
    Long total = queryFactory
        .select(rv.count())
        .from(rv)
        .join(rp).on(rp.id.eq(rv.reportId))
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
