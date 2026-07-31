package com.soma.backend.domain.adjuster.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.report.entity.QReport;
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

  /** 검수 대기 풀 카운트(global) — AWAITING_INSPECTION + AWAITING_ADOPTION. */
  public long countPendingPool() {
    QReport rp = QReport.report;
    Long count = queryFactory
        .select(rp.count())
        .from(rp)
        .where(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /** 검수 대기 풀 중 신규(threshold 이후 접수) 카운트. threshold는 서비스가 잠정 규칙으로 계산한다. */
  public long countPendingPoolNew(LocalDateTime newThreshold) {
    QReport rp = QReport.report;
    Long count = queryFactory
        .select(rp.count())
        .from(rp)
        .where(rp.status.in(ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION)
            .and(rp.createdAt.goe(newThreshold)))
        .fetchOne();
    return count == null ? 0L : count;
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
