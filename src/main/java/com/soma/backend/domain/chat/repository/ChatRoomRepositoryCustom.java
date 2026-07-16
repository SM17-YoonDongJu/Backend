package com.soma.backend.domain.chat.repository;

import java.util.List;
import java.util.UUID;

/** ChatRoom 동적 조회(목록 unread + review_status 조인) QueryDSL 프래그먼트. */
public interface ChatRoomRepositoryCustom {

  /** 내가 참여한 방 목록(최근 활동순). 안읽음 수·제안 상태를 함께 조회한다. */
  List<ChatRoomListRow> findMyRoomRows(UUID me);
}
