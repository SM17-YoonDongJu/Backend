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
    QReportReview rv = QReportReview.reportReview;

    BooleanBuilder where = new BooleanBuilder();
    // 검수 대기 목록은 검수 단계(AWAITING_INSPECTION·AWAITING_ADOPTION, 상세 조회 노출 정책과 동일)만 노출한다.
    // CLOSED·COUNSELING·NOT_SELECTED가 목록에 새어 클릭 시 상세가 404 나던 불일치를 막는다.
    where.and(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION));
    // 요청 사정사가 보류(report_holds)한 리포트는 목록에서 숨긴다(보류 = 내 대기열에서 제외). count 쿼리도 같은 where라 함께 줄어든다.
    where.and(JPAExpressions.selectOne().from(rh)
        .where(rh.reportId.eq(rp.id).and(rh.adjusterId.eq(adjusterId))).notExists());
    // 본인이 이미 검수 진행 중(SENT·COUNSELING)인 리포트도 숨긴다 — 내가 SENT 한 AWAITING_ADOPTION 건은
    // 진행중으로 넘어갔으므로 검수 대기(아직 안 본 큐)에서 제외한다(진행중∩검수대기=∅).
    where.and(JPAExpressions.selectOne().from(rv)
        .where(rv.reportId.eq(rp.id).and(rv.adjusterId.eq(adjusterId))
            .and(rv.status.in(ReviewStatus.SENT, ReviewStatus.COUNSELING))).notExists());
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
    return queryUserReportCards(userId, status, pageable);
  }

  @Override
  public Page<ReportCardRow> findReportsWithProposals(UUID userId, Pageable pageable) {
    return queryReportCards(userId, null, true, pageable);
  }

  /**
   * 내 리포트 카드 목록 조회(GET /reports) — 리포트(rp) 기준 LEFT JOIN. 소유자 리포트를 빠짐없이 노출하기 위해
   * report_reviews를 LEFT JOIN 한다: 리뷰가 있으면 기존처럼 1건당 1행(per-review, 리포트당 리뷰 N개면 N행),
   * 리뷰가 0건이면 카드 1행이 나오고 reviewedAt·adjusterNickname은 null·proposalCount는 0이다.
   * 정렬은 리포트 접수 최신순(rp.createdAt desc)이 우선이고, 그다음 리뷰 최신순(리뷰 없는 행은 nulls-last —
   * Postgres desc 기본값이 nulls-first라 명시가 필요하다)이다. rp.createdAt이 동률인 리포트가 여럿(특히
   * 무리뷰 리포트끼리)이면 이 두 키만으로는 정렬이 유일하지 않아 페이지 경계에서 행이 중복/누락될 수 있어
   * rp.id·rv.id를 타이브레이커로 덧붙인다. count도 content와 동일한 FROM/JOIN/WHERE에 non-distinct count라
   * total이 실제 카드 행 수와 일치한다(countDistinct를 쓰면 per-review fan-out이 total에 반영되지 않아
   * 페이지네이션이 어긋난다).
   */
  private Page<ReportCardRow> queryUserReportCards(UUID userId, ReportStatus status, Pageable pageable) {
    QReport rp = QReport.report;
    QReportReview rv = QReportReview.reportReview;
    QReportReview sibling = new QReportReview("sibling");
    QUser au = QUser.user;

    BooleanBuilder where = new BooleanBuilder();
    where.and(rp.userId.eq(userId));
    if (status != null) {
      where.and(rp.status.eq(status));
    }

    List<ReportCardRow> content = queryFactory
        .select(Projections.constructor(ReportCardRow.class,
            rp.id, rp.status, rp.accidentType, rp.title, rp.createdAt, rp.caseNo,
            rp.claimedMinAmount, rp.claimedMaxAmount,
            // proposalCount = REJECTED 제외 리뷰 수(리포트 단위). 무리뷰 리포트는 0이 된다.
            JPAExpressions.select(sibling.count()).from(sibling)
                .where(sibling.reportId.eq(rp.id).and(sibling.status.ne(ReviewStatus.REJECTED))),
            rv.updatedAt, au.nickname, rp.offeredAmount, rp.treatment))
        .from(rp)
        .leftJoin(rv).on(rv.reportId.eq(rp.id))
        .leftJoin(au).on(au.id.eq(rv.adjusterId))
        .where(where)
        // rp.id·rv.id 타이브레이커: created_at이 동률(특히 무리뷰 리포트끼리)이면 페이지 경계에서
        // 순서가 흔들려 같은 행이 중복 노출되거나 아예 빠질 수 있어, 정렬을 항상 유일하게 고정한다.
        .orderBy(rp.createdAt.desc(), rv.createdAt.desc().nullsLast(), rp.id.desc(), rv.id.desc().nullsLast())
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();

    // content와 같은 FROM/LEFT JOIN/WHERE로 세야 리뷰 fan-out·무리뷰 1행이 total에 그대로 반영된다.
    Long total = queryFactory
        .select(rp.count())
        .from(rp)
        .leftJoin(rv).on(rv.reportId.eq(rp.id))
        .where(where)
        .fetchOne();

    return new PageImpl<>(content, pageable, total == null ? 0L : total);
  }

  /**
   * 받은 제안 목록 전용 조회(GET /me/received-proposals) — per-review. 소유자(rp.userId) 리포트에 달린
   * report_reviews를 1건당 1행으로 편다(리포트당 리뷰 N개면 N행). {@code excludeRejected}면 REJECTED 리뷰 행을
   * 제외한다. status가 있으면 리포트 상태로 필터한다. reviewedAt·adjusterNickname은 그 리뷰값, proposalCount는
   * REJECTED 제외 리뷰 수(리포트 단위 상관 서브쿼리), 나머지(accidentType·claimed·offered·treatment)는 리포트값이다.
   *
   * <p>주의: 제안(리뷰)이 있어야 의미 있는 목록이라 {@code from(rv).join(rp)} INNER JOIN 의미를 깨지 마세요.
   * 무리뷰 리포트까지 노출해야 하는 내 리포트 목록은 {@link #queryUserReportCards}(LEFT JOIN)를 씁니다.
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
