package com.soma.backend.domain.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import com.soma.backend.domain.chat.entity.QChatMessage;
import com.soma.backend.domain.chat.entity.QChatRoom;
import com.soma.backend.domain.report.entity.QReport;
import com.soma.backend.domain.report.entity.QReportReview;
import com.soma.backend.domain.user.entity.QUser;

/**
 * ChatRoom 목록 조회 QueryDSL 구현. native 없이(하네스 규칙) 상관 서브쿼리로 안읽음 수를 계산하고,
 * report_reviews·users를 엔티티 조인해 제안 상태·상대방 이름을 함께 가져온다(크로스-애그리거트 읽기).
 */
public class ChatRoomRepositoryImpl implements ChatRoomRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  public ChatRoomRepositoryImpl(JPAQueryFactory queryFactory) {
    this.queryFactory = queryFactory;
  }

  @Override
  public List<ChatRoomListRow> findMyRoomRows(UUID me) {
    return baseRoomQuery(me)
        .orderBy(QChatRoom.chatRoom.lastMessageAt.desc().nullsLast())
        .fetch();
  }

  @Override
  public Optional<ChatRoomListRow> findMyRoomRow(UUID me, UUID chatRoomId) {
    return Optional.ofNullable(
        baseRoomQuery(me).where(QChatRoom.chatRoom.id.eq(chatRoomId)).fetchOne());
  }

  /**
   * GET /chats(목록)·GET /chats/{chatRoomId}(단건 상세) 공통 기반 쿼리. 상관 서브쿼리로 안읽음 수를 계산하고,
   * report_reviews·users를 엔티티 조인해 제안 상태·상대방 이름을 함께 가져온다(크로스-애그리거트 읽기).
   * 참여자(user·adjuster) 필터까지만 걸고, 정렬(목록)·단건 조건은 호출부가 덧붙인다.
   */
  private JPAQuery<ChatRoomListRow> baseRoomQuery(UUID me) {
    QChatRoom room = QChatRoom.chatRoom;
    QChatMessage message = QChatMessage.chatMessage;
    QReportReview review = QReportReview.reportReview;
    QReport report = QReport.report;
    QUser userAccount = new QUser("userAccount");
    QUser adjusterAccount = new QUser("adjusterAccount");

    // 안읽음 = 상대가 보낸 메시지(내 것·SYSTEM 제외) 중 내 읽음 커서 이후. me는 user·adjuster 중 하나이므로
    // 해당 분기만 참이 된다(별도 CASE 없이 OR로 분기).
    BooleanExpression readAsUser = room.userId.eq(me)
        .and(room.userLastReadAt.isNull().or(message.createdAt.gt(room.userLastReadAt)));
    BooleanExpression readAsAdjuster = room.adjusterId.eq(me)
        .and(room.adjusterLastReadAt.isNull().or(message.createdAt.gt(room.adjusterLastReadAt)));
    Expression<Long> unreadCount = JPAExpressions
        .select(message.count())
        .from(message)
        .where(message.roomId.eq(room.id)
            .and(message.senderId.isNotNull())
            .and(message.senderId.ne(me))
            .and(readAsUser.or(readAsAdjuster)));

    return queryFactory
        .select(Projections.constructor(ChatRoomListRow.class,
            room.id,
            room.reportId,
            room.reportReviewId,
            room.status,
            review.status,
            report.caseNo,
            report.accidentType,
            room.userId,
            room.adjusterId,
            userAccount.nickname,
            adjusterAccount.nickname,
            userAccount.avatarUrl,
            adjusterAccount.avatarUrl,
            room.lastMessage,
            room.lastMessageAt,
            unreadCount,
            room.createdAt))
        .from(room)
        .leftJoin(review).on(review.id.eq(room.reportReviewId))
        .leftJoin(report).on(report.id.eq(room.reportId))
        .leftJoin(userAccount).on(userAccount.id.eq(room.userId))
        .leftJoin(adjusterAccount).on(adjusterAccount.id.eq(room.adjusterId))
        .where(room.userId.eq(me).or(room.adjusterId.eq(me)));
  }
}
