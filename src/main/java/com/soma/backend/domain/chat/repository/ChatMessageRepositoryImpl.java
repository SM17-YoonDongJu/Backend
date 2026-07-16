package com.soma.backend.domain.chat.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.QChatMessage;

/**
 * ChatMessage 커서 페이지네이션 QueryDSL 구현. 정렬은 {@code created_at DESC, id DESC}이며 커서는
 * (createdAt, id) 튜플 미만을 뽑는다(동시각 tie는 id로 안정 정렬).
 */
public class ChatMessageRepositoryImpl implements ChatMessageRepositoryCustom {

  private final JPAQueryFactory queryFactory;

  public ChatMessageRepositoryImpl(JPAQueryFactory queryFactory) {
    this.queryFactory = queryFactory;
  }

  @Override
  public List<ChatMessage> findByCursor(UUID roomId, LocalDateTime cursorCreatedAt, UUID cursorId, int limit) {
    QChatMessage message = QChatMessage.chatMessage;
    BooleanBuilder where = new BooleanBuilder();
    where.and(message.roomId.eq(roomId));
    if (cursorCreatedAt != null && cursorId != null) {
      where.and(message.createdAt.lt(cursorCreatedAt)
          .or(message.createdAt.eq(cursorCreatedAt).and(message.id.lt(cursorId))));
    }
    return queryFactory
        .selectFrom(message)
        .where(where)
        .orderBy(message.createdAt.desc(), message.id.desc())
        .limit(limit)
        .fetch();
  }
}
