package com.soma.backend.domain.user.repository;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.adjuster.entity.QAdjusterProfile;
import com.soma.backend.domain.chat.entity.QChatMessage;
import com.soma.backend.domain.chat.entity.QChatRoom;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.user.entity.QUser;

/**
 * 고객 홈 대시보드 크로스-애그리거트 읽기 모델(QueryDSL, 조회 전용). reports·report_reviews·adjuster_profiles·
 * users·chat_rooms(_messages)를 읽어 대표 활성 리포트·제안 요약·"지금 할 일"(todos) 카운트·대표 안읽음 채팅을
 * 조립한다. 쓰기는 없다 — 각 Aggregate 상태 변경은 소유 Repository만 담당하고 여기서는 읽기 프로젝션만 만든다
 * (사정사 홈 AdjusterHomeRepository 관례). native 미사용(하네스 규칙): 조인 대상이 전부 엔티티로 매핑돼 있다.
 */
@Repository
@RequiredArgsConstructor
public class UserDashboardRepository {

  /** '활성' 리포트 상태 — 종료(CLOSED)·미채택(NOT_SELECTED)을 제외한 진행 중 상태. */
  private static final Set<ReportStatus> ACTIVE_STATUSES = EnumSet.of(
      ReportStatus.AWAITING_INSPECTION, ReportStatus.AWAITING_ADOPTION, ReportStatus.COUNSELING);

  private final JPAQueryFactory queryFactory;

  /** 대표 활성 리포트 — 소유자의 활성 상태 중 가장 최근 접수 1건. 없으면 empty. */
  public Optional<ActiveReportRow> findLatestActiveReport(UUID userId) {
    QReport rp = QReport.report;
    return Optional.ofNullable(queryFactory
        .select(Projections.constructor(ActiveReportRow.class,
            rp.id, rp.title, rp.accidentType, rp.status, rp.createdAt))
        .from(rp)
        .where(rp.userId.eq(userId).and(rp.status.in(ACTIVE_STATUSES)))
        .orderBy(rp.createdAt.desc())
        .limit(1)
        .fetchOne());
  }

  /** 리포트 최초 검수 시각 — min(REPORT_REVIEWS.created_at). 검수가 하나도 없으면 null(집계 대상 없음). */
  public LocalDateTime findFirstReviewedAt(UUID reportId) {
    QReportReview rv = QReportReview.reportReview;
    return queryFactory
        .select(rv.createdAt.min())
        .from(rv)
        .where(rv.reportId.eq(reportId))
        .fetchOne();
  }

  /**
   * 대표 리포트에 달린 제안(거절 제외) 항목 — 최근 등록순. 사정사 users로 이름을, adjuster_profiles로
   * 연차·전문분야를 조인한다(프로필 미존재 시 career·specialties는 null). 견적 집계(count·min·max·avg)는
   * 이 목록에서 서비스가 파생하므로 별도 집계 쿼리를 두지 않는다.
   */
  public List<ProposalItemRow> findProposalItems(UUID reportId) {
    QReportReview rv = QReportReview.reportReview;
    QUser us = QUser.user;
    QAdjusterProfile ap = QAdjusterProfile.adjusterProfile;
    return queryFactory
        .select(Projections.constructor(ProposalItemRow.class,
            rv.id, rv.adjusterId, us.nickname, ap.career, ap.specialties,
            rv.estimateMinAmount, rv.estimateMaxAmount))
        .from(rv)
        .join(us).on(us.id.eq(rv.adjusterId))
        .leftJoin(ap).on(ap.userId.eq(rv.adjusterId))
        .where(rv.reportId.eq(reportId).and(rv.status.ne(ReviewStatus.REJECTED)))
        .orderBy(rv.createdAt.desc())
        .fetch();
  }

  /** 할 일 — 소유자의 리포트에 달린 미확인 신규 제안(SENT) 수. */
  public long countUnreadProposals(UUID userId) {
    QReportReview rv = QReportReview.reportReview;
    QReport rp = QReport.report;
    Long count = queryFactory
        .select(rv.count())
        .from(rv)
        .join(rp).on(rp.id.eq(rv.reportId))
        .where(rp.userId.eq(userId).and(rv.status.eq(ReviewStatus.SENT)))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /** 할 일 — 소유자의 검수 완료(채택 대기, AWAITING_ADOPTION) 리포트 수. reviewed_at 컬럼이 없어 상태로 판정한다. */
  public long countUnreadReviewCompleted(UUID userId) {
    QReport rp = QReport.report;
    Long count = queryFactory
        .select(rp.count())
        .from(rp)
        .where(rp.userId.eq(userId).and(rp.status.eq(ReportStatus.AWAITING_ADOPTION)))
        .fetchOne();
    return count == null ? 0L : count;
  }

  /**
   * 대표 안읽음 채팅 1건 — 요청 사용자가 고객(room.user_id)인 방 중 안읽음이 있는 방을 최근 메시지순으로 하나
   * 고른다. 안읽음 판정은 상대(사정사)가 보낸 메시지(내 것·SYSTEM 제외) 중 내 읽음 커서 이후 존재 여부다.
   * adjuster_nickname은 non-null 계약이라 사정사 users를 inner join한다.
   */
  public Optional<UnreadChatRow> findTopUnreadChat(UUID userId) {
    QChatRoom room = QChatRoom.chatRoom;
    QChatMessage msg = new QChatMessage("unreadMsg");
    QUser adjuster = QUser.user;

    BooleanExpression hasUnread = JPAExpressions
        .selectOne()
        .from(msg)
        .where(msg.roomId.eq(room.id)
            .and(msg.senderId.isNotNull())
            .and(msg.senderId.ne(userId))
            .and(room.userLastReadAt.isNull().or(msg.createdAt.gt(room.userLastReadAt))))
        .exists();

    return Optional.ofNullable(queryFactory
        .select(Projections.constructor(UnreadChatRow.class,
            room.id, adjuster.nickname, room.lastMessage))
        .from(room)
        .join(adjuster).on(adjuster.id.eq(room.adjusterId))
        .where(room.userId.eq(userId).and(hasUnread))
        .orderBy(room.lastMessageAt.desc().nullsLast())
        .limit(1)
        .fetchOne());
  }
}
