package com.soma.backend.domain.chat.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatMessage;

/** ChatMessage 커서 페이지네이션 QueryDSL 프래그먼트. */
public interface ChatMessageRepositoryCustom {

  /**
   * 방 메시지를 최신순({@code created_at DESC, id DESC})으로 조회한다. 커서가 있으면 그 (createdAt, id)
   * 미만만 가져오며, {@code limit}은 has_next 판별을 위해 보통 size+1로 넘긴다.
   */
  List<ChatMessage> findByCursor(UUID roomId, LocalDateTime cursorCreatedAt, UUID cursorId, int limit);
}
