package com.soma.backend.domain.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** ChatRoom 동적 조회(목록 unread + review_status 조인) QueryDSL 프래그먼트. */
public interface ChatRoomRepositoryCustom {

  /**
   * 내가 참여한 방 목록 한 페이지(keyset). 안읽음 수·제안 상태를 함께 조회한다.
   * 정렬은 {@code last_message_at DESC NULLS LAST, id DESC}이고, {@code cursor}가 있으면 그 위치 다음 행부터 돌려준다.
   *
   * @param cursor null이면 첫 페이지
   * @param limit 최대 행 수. 다음 페이지 판별을 위해 호출부는 보통 페이지 크기 + 1을 넘긴다
   */
  List<ChatRoomListRow> findMyRoomRows(UUID me, @Nullable ChatRoomCursor cursor, int limit);

  /** 내가 참여한 단건 방(GET /chats/{chatRoomId}). 미참여·미존재면 empty. */
  Optional<ChatRoomListRow> findMyRoomRow(UUID me, UUID chatRoomId);
}
