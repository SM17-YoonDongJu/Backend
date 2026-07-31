package com.soma.backend.domain.adjuster.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportHold;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.QUser;

/**
 * 사정사 홈 대시보드 크로스-애그리거트 읽기 모델(QueryDSL, 조회 전용). adjuster_profiles·users·reports·
 * report_reviews를 읽어 요약 카드/진행 중 사건을 집계한다. 쓰기는 없다 — 각 Aggregate의 상태 변경은 소유
 * Repository(Report·ReportReview·AdjusterProfile)만 담당하고, 여기서는 읽기 프로젝션만 조립한다.
 */
@Repository
@RequiredArgsConstructor
public class AdjusterHomeRepository {

  private final JPAQueryFactory queryFactory;

  /** 홈 헤더·요약 비정규화(users LEFT JOIN adjuster_profiles). 사용자가 없으면 null. */
  public AdjusterIdentityRow findAdjusterIdentity(UUID userId) {
    QUser us = QUser.user;
    QAdjusterProfile ap = QAdjusterProfile.adjusterProfile;
    return queryFactory
        .select(Projections.constructor(AdjusterIdentityRow.class,
            ap.name.coalesce(us.nickname),
            us.avatarUrl,
            ap.completedConsultCount,
            ap.ratingMean,
            ap.reviewCount))
        .from(us)
        .leftJoin(ap).on(ap.userId.eq(us.id))
        .where(us.id.eq(userId))
        .fetchOne();
  }

  /**
   * 검수 대기 풀(AWAITING_INSPECTION + AWAITING_ADOPTION) 중 요청 사정사가 보류(report_holds)하지 않은 카운트.
   * 보류를 누르면 홈·마이페이지·검수대기 요약의 '검수 대기' 수가 함께 줄도록 개인화한다(숫자 정합).
   */
  public long countPendingPoolNotHeldBy(UUID adjusterId) {
    QReport rp = QReport.report;
    Long count = queryFactory
        .select(rp.count())
        .from(rp)
        .where(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION)
            .and(notHeldBy(rp, adjusterId)))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /** 검수 대기 풀(보류 제외) 중 신규(threshold 이후 접수) 카운트. threshold는 서비스가 잠정 규칙으로 계산한다. */
  public long countPendingPoolNewNotHeldBy(LocalDateTime newThreshold, UUID adjusterId) {
    QReport rp = QReport.report;
    Long count = queryFactory
        .select(rp.count())
        .from(rp)
        .where(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION)
            .and(rp.createdAt.goe(newThreshold))
            .and(notHeldBy(rp, adjusterId)))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /** 요청 사정사가 해당 리포트를 보류(report_holds)하지 않았는지(notExists) 술어 — 검수 대기 카운트를 개인화한다. */
  private BooleanExpression notHeldBy(QReport rp, UUID adjusterId) {
    QReportHold rh = QReportHold.reportHold;
    return JPAExpressions.selectOne().from(rh)
        .where(rh.reportId.eq(rp.id).and(rh.adjusterId.eq(adjusterId))).notExists();
  }

  /** 요청 사정사의 진행 중(미완료) 검수 카운트 — SENT·COUNSELING. */
  public long countInProgress(UUID adjusterId) {
    QReportReview rv = QReportReview.reportReview;
    Long count = queryFactory
        .select(rv.count())
        .from(rv)
        .where(rv.adjusterId.eq(adjusterId)
            .and(rv.status.in(ReviewStatus.SENT, ReviewStatus.COUNSELING)))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /** 요청 사정사의 기간 내 검수 완료 카운트(이번 달 실시간 집계용). 범위는 [from, to). */
  public long countCompletedBetween(UUID adjusterId, LocalDateTime from, LocalDateTime to) {
    QReportReview rv = QReportReview.reportReview;
    Long count = queryFactory
        .select(rv.count())
        .from(rv)
        .where(rv.adjusterId.eq(adjusterId)
            .and(rv.createdAt.goe(from))
            .and(rv.createdAt.lt(to)))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /** 진행 중 사건 미리보기(top N) — 최근 작업순. limit은 서비스가 [1,20]로 clamp해서 넘긴다. */
  public List<InProgressCaseRow> findInProgressCases(UUID adjusterId, int limit) {
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
        .limit(limit)
        .fetch();
  }
}
