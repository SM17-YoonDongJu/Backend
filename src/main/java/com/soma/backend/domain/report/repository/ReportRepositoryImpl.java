package com.soma.backend.domain.report.repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.QInsuranceProduct;
import com.soma.backend.domain.report.entity.QInsurer;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportHold;
import com.soma.backend.domain.report.entity.QReportIssue;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.QUserClaim;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.QUser;

/** Report 동적 조회 QueryDSL 구현. issueCount·held는 상관 서브쿼리, users 조인은 엔티티 조인(on)으로 처리한다. */
@Slf4j
@RequiredArgsConstructor
public class ReportRepositoryImpl implements ReportRepositoryCustom {

  private static final String FORCE_CUSTOM_PLAN = "SET LOCAL plan_cache_mode = force_custom_plan";

  private final JPAQueryFactory queryFactory;
  private final EntityManager entityManager;

  @Override
  public List<PendingReviewRow> findPendingReviewRows(
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId,
      Set<AccidentType> specialtyTypes, Pageable pageable) {
    useCustomPlanForThisTransaction();
    QReport rp = QReport.report;
    QUser us = QUser.user;
    QReportIssue ri = QReportIssue.reportIssue;

    // 2단계 조회: 커버링 인덱스(V48)로 index-only scan이 되려면 select 컬럼이 전부 인덱스 안에 있어야 한다.
    // 그래서 먼저 조건·정렬·offset/limit을 id만 꺼내는 쿼리로 끝내고, 상세 컬럼과 issueCount 서브쿼리는
    // 그 페이지 id(최대 page size개)에만 붙인다. 두 쿼리 사이 동시 변경으로 한 행이 빠질 수 있으나 읽기 전용
    // 목록이라 허용한다(채팅방 목록 #371과 같은 판단).
    List<UUID> ids = queryFactory
        .select(rp.id)
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(pendingReviewCondition(rp, us, status, accidentType, region, adjusterId))
        .orderBy(pendingReviewOrder(rp, specialtyTypes))
        .offset(pageable.getOffset())
        .limit(pageable.getPageSize())
        .fetch();
    if (ids.isEmpty()) {
      return List.of();
    }

    return queryFactory
        .select(Projections.constructor(PendingReviewRow.class,
            rp.id, rp.caseNo, rp.title, rp.accidentType, us.region, rp.status,
            rp.claimedMinAmount, rp.claimedMaxAmount, rp.offeredAmount,
            JPAExpressions.select(ri.count()).from(ri).where(ri.reportId.eq(rp.id)),
            // 보류 건은 where에서 제외되므로 held는 항상 false다. FE 계약 유지를 위해 필드만 남긴다.
            Expressions.constant(false),
            rp.createdAt))
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(rp.id.in(ids))
        .orderBy(pendingReviewOrder(rp, specialtyTypes))
        .fetch();
  }

  @Override
  public long countPendingReviewRows(
      ReportStatus status, AccidentType accidentType, String region, UUID adjusterId) {
    useCustomPlanForThisTransaction();
    QReport rp = QReport.report;
    QUser us = QUser.user;
    // 목록과 같은 조인·조건이어야 페이지 수가 맞는다(users 조인은 region 필터와 고아 리포트 제외에 필요).
    Long total = queryFactory
        .select(rp.count())
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .where(pendingReviewCondition(rp, us, status, accidentType, region, adjusterId))
        .fetchOne();
    return total == null ? 0L : total;
  }

  /**
   * 검수대기 목록·건수 쿼리가 이 트랜잭션에서 custom plan을 쓰게 한다(#378). 문서화된 native 예외 — 조회가 아니라
   * 세션 설정이고, 조회 쿼리 자체는 QueryDSL 그대로다.
   *
   * <p>pgjdbc는 같은 SQL이 5번 넘게 실행되면 서버 측 prepared statement로 바꾸고, PostgreSQL은 그 뒤 바인딩 값을
   * 모르는 generic plan을 고를 수 있다. 이 쿼리는 사정사 ID·지역 값에 따라 선택도가 크게 달라 generic plan이
   * Nested Loop Anti Join으로 굳는데, dev 재현에서 generic 68.5ms(약 48,000블록) vs custom 8.7ms(Hash Right Anti
   * Join)였다. 전역(prepareThreshold·JDBC URL)으로 바꾸면 다른 쿼리의 plan 캐시 이점까지 잃으니 이 경로에만 건다.
   *
   * <p>LOCAL인 이유: 커밋·롤백과 함께 원래 값(auto)으로 돌아가 커넥션 풀로 설정이 새지 않는다. 트랜잭션 밖에서는
   * 경고만 나고 효과가 없으므로 왕복 자체를 생략한다. 목록·건수 중 무엇이 먼저 불려도 적용되도록 둘 다 부르며,
   * 같은 트랜잭션에서 두 번 실행돼도 무해하다. JPA {@code executeUpdate} 대신 JDBC로 실행하는 건 native 변경
   * 쿼리로 취급돼 Hibernate 쿼리 통계에 잡히고 2차 캐시 영역 무효화 대상이 되는 부수효과를 피하기 위해서다.
   */
  private void useCustomPlanForThisTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      log.debug("트랜잭션 밖 검수대기 조회라 plan_cache_mode 설정을 건너뜀");
      return;
    }
    entityManager.unwrap(Session.class).doWork(connection -> {
      try (Statement statement = connection.createStatement()) {
        statement.execute(FORCE_CUSTOM_PLAN);
      }
    });
  }

  /** 검수대기 목록·건수 공용 조건. 두 쿼리가 어긋나지 않게 한 곳에서만 만든다. */
  private static BooleanBuilder pendingReviewCondition(
      QReport rp, QUser us, ReportStatus status, AccidentType accidentType, String region, UUID adjusterId) {
    BooleanBuilder where = new BooleanBuilder();
    // 검수 대기 목록은 검수 단계(AWAITING_INSPECTION·AWAITING_ADOPTION, 상세 조회 노출 정책과 동일)만 노출한다.
    // CLOSED·COUNSELING·NOT_SELECTED가 목록에 새어 클릭 시 상세가 404 나던 불일치를 막는다.
    where.and(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION));
    // 내가 보류했거나 이미 검수 진행 중인 리포트는 숨긴다. count도 이 조건을 공유해 함께 줄어든다.
    where.and(inMyPendingQueue(rp, adjusterId));
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
    return where;
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
    // created_at 동률이면 순서가 유일하지 않아 페이지 경계에서 중복·누락이 생기고, ID 단계와 상세 단계의 순서도
    // 어긋날 수 있다. id(커버링 인덱스 INCLUDE 컬럼)를 타이브레이커로 둔다.
    orders.add(rp.id.asc());
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

  @Override
  public ReviewContextRow findReviewContext(UUID reportId) {
    QReport rp = QReport.report;
    QUser us = QUser.user;
    QUserClaim uc = QUserClaim.userClaim;
    QInsuranceProduct ip = QInsuranceProduct.insuranceProduct;
    QInsurer ins = QInsurer.insurer;

    return queryFactory
        .select(Projections.constructor(ReviewContextRow.class,
            us.nickname, us.gender, us.birthDate, us.createdAt,
            uc.accidentType, uc.accidentDate, ip.productName, ins.name))
        .from(rp)
        .join(us).on(us.id.eq(rp.userId))
        .leftJoin(uc).on(uc.id.eq(rp.claimId))
        .leftJoin(ip).on(ip.id.eq(rp.productId))
        .leftJoin(ins).on(ins.id.eq(ip.insurerId))
        .where(rp.id.eq(reportId))
        .fetchOne();
  }

  @Override
  public long countPendingNotHeldBy(UUID adjusterId) {
    QReport rp = QReport.report;
    return countReports(rp, rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION)
        .and(inMyPendingQueue(rp, adjusterId)));
  }

  @Override
  public long countDueSoonNotHeldBy(LocalDateTime dueSoonThreshold, UUID adjusterId) {
    QReport rp = QReport.report;
    return countReports(rp, rp.status.eq(ReportStatus.AWAITING_INSPECTION)
        .and(rp.createdAt.loe(dueSoonThreshold))
        .and(inMyPendingQueue(rp, adjusterId)));
  }

  @Override
  public long countPendingByAccidentTypeInNotHeldBy(Collection<AccidentType> types, UUID adjusterId) {
    QReport rp = QReport.report;
    return countReports(rp, rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION)
        .and(rp.accidentType.in(types))
        .and(inMyPendingQueue(rp, adjusterId)));
  }

  private long countReports(QReport rp, BooleanExpression condition) {
    Long count = queryFactory.select(rp.count()).from(rp).where(condition).fetchOne();
    return count == null ? 0L : count;
  }

  /**
   * 요청 사정사의 '내 대기 큐'에 남은 리포트 조건 — 본인이 보류(report_holds)하지도, 이미 검수 진행
   * (report_reviews SENT·COUNSELING)하지도 않았다. 내가 SENT 한 AWAITING_ADOPTION 건은 진행중으로 넘어갔으므로
   * 대기 큐에서 뺀다(진행중∩검수대기=∅). 검수대기 목록과 요약 카운트 세 개가 이 정의를 공유한다.
   */
  private static BooleanExpression inMyPendingQueue(QReport rp, UUID adjusterId) {
    QReportHold hold = new QReportHold("myHold");
    QReportReview myReview = new QReportReview("myReview");
    return JPAExpressions.selectOne().from(hold)
        .where(hold.reportId.eq(rp.id).and(hold.adjusterId.eq(adjusterId))).notExists()
        .and(JPAExpressions.selectOne().from(myReview)
            .where(myReview.reportId.eq(rp.id).and(myReview.adjusterId.eq(adjusterId))
                .and(myReview.status.in(ReviewStatus.SENT, ReviewStatus.COUNSELING))).notExists());
  }
}
