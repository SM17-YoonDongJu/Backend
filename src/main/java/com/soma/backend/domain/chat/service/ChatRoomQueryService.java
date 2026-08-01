package com.soma.backend.domain.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatRoomDetailResponse;
import com.soma.backend.domain.chat.dto.ChatRoomListResponse;
import com.soma.backend.domain.chat.dto.ChatRoomSummaryResponse;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 채팅방 목록·단건 상세 조회(설계서 §4 ①). 상대방 이름을 me 기준으로 결정하고 안읽음 수를 포함한다. */
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

  /** GET /chats/{chatRoomId} — 딥링크/새로고침 시 채팅 헤더용 단건 상세. 미참여·미존재면 404. */
  @Transactional(readOnly = true)
  public ChatRoomDetailResponse getRoom(UUID me, UUID chatRoomId) {
    ChatRoomListRow row = chatRoomRepository.findMyRoomRow(me, chatRoomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    return new ChatRoomDetailResponse(
        row.chatRoomId(),
        row.reportId(),
        row.reportReviewId(),
        row.status(),
        row.reviewStatus(),
        row.caseNo(),
        row.accidentType(),
        resolveCounterpart(row, me),
        row.lastMessage(),
        row.lastMessageAt(),
        unread(row),
        row.createdAt());
  }

  private ChatRoomSummaryResponse toSummary(ChatRoomListRow row, UUID me) {
    return new ChatRoomSummaryResponse(
        row.chatRoomId(),
        row.reportId(),
        row.reportReviewId(),
        row.status(),
        row.reviewStatus(),
        row.caseNo(),
        row.accidentType(),
        resolveCounterpart(row, me),
        row.lastMessage(),
        row.lastMessageAt(),
        unread(row));
  }

  /** 상대방(고객↔사정사)을 me 기준으로 결정한다. me가 user면 상대는 adjuster, 아니면 user. */
  private ChatRoomSummaryResponse.Counterpart resolveCounterpart(ChatRoomListRow row, UUID me) {
    boolean iAmUser = me.equals(row.userId());
    UUID counterpartId = iAmUser ? row.adjusterId() : row.userId();
    String counterpartName = iAmUser ? row.adjusterNickname() : row.userNickname();
    String counterpartAvatarUrl = iAmUser ? row.adjusterAvatarUrl() : row.userAvatarUrl();
    return new ChatRoomSummaryResponse.Counterpart(counterpartId, counterpartName, counterpartAvatarUrl);
  }

  private long unread(ChatRoomListRow row) {
    return row.unreadCount() == null ? 0L : row.unreadCount();
  }
}
