package com.soma.backend.domain.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;

/** 채팅방 목록 조회(설계서 §4 ①). 상대방 이름을 me 기준으로 결정하고 안읽음 수를 포함한다. */
@Service
@RequiredArgsConstructor
public class ChatRoomQueryService {

  private final ChatRoomRepository chatRoomRepository;

  @Transactional(readOnly = true)
  public ChatRoomListResponse listMyRooms(UUID me) {
    List<ChatRoomSummaryResponse> rooms = chatRoomRepository.findMyRoomRows(me).stream()
        .map(row -> toSummary(row, me))
        .toList();
    return new ChatRoomListResponse(rooms);
  }

  private ChatRoomSummaryResponse toSummary(ChatRoomListRow row, UUID me) {
    boolean iAmUser = me.equals(row.userId());
    UUID counterpartId = iAmUser ? row.adjusterId() : row.userId();
    String counterpartName = iAmUser ? row.adjusterNickname() : row.userNickname();
    String counterpartAvatarUrl = iAmUser ? row.adjusterAvatarUrl() : row.userAvatarUrl();
    long unread = row.unreadCount() == null ? 0L : row.unreadCount();
    return new ChatRoomSummaryResponse(
        row.chatRoomId(),
        row.reportId(),
        row.reportReviewId(),
        row.status(),
        row.reviewStatus(),
        row.caseNo(),
        row.accidentType(),
        new ChatRoomSummaryResponse.Counterpart(counterpartId, counterpartName, counterpartAvatarUrl),
        row.lastMessage(),
        row.lastMessageAt(),
        unread);
  }
}
