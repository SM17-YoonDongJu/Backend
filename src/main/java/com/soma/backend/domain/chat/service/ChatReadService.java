package com.soma.backend.domain.chat.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ReadResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 읽음 처리(설계서 §4 ⑥). 내 읽음 커서만 현재 시각으로 갱신해 이후 목록의 unread_count를 0으로 만든다. */
@Service
@RequiredArgsConstructor
public class ChatReadService {

  private final ChatRoomRepository chatRoomRepository;

  @Transactional
  public ReadResponse read(UUID me, UUID roomId) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
    LocalDateTime now = LocalDateTime.now();
    room.markRead(me, now);
    return new ReadResponse(roomId, now);
  }
}
