package com.soma.backend.domain.chat.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatReportRequest;
import com.soma.backend.domain.chat.dto.ChatReportResponse;
import com.soma.backend.domain.chat.entity.ChatReport;
import com.soma.backend.domain.chat.entity.ChatReportReason;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatReportRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 채팅방 신고 접수. 인가(참여자 확인) + 조립 + 저장만 담당하고 입력 규칙은 VO·엔티티가 지킨다.
 * ChatRoom은 가드용으로 읽기만 하며 어떤 필드도 수정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChatReportCommandService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatReportRepository chatReportRepository;

  /**
   * 채팅방 신고 접수. 참여자만 가능하며 중복 신고를 허용한다(매번 새 행). 방 상태(CLOSED 포함)와
   * 무관하게 접수하고, 방 상태를 바꾸지 않는다.
   */
  @Transactional
  public ChatReportResponse report(UUID me, UUID roomId, ChatReportRequest request) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }

    // counterpartOf는 me가 userId가 아니면 무조건 userId를 반환하므로 isMember 통과 이후에만 호출한다.
    UUID reportedId = room.counterpartOf(me);
    ChatReportReason reason = ChatReportReason.from(request.reason());
    ChatReport saved = chatReportRepository.save(
        ChatReport.create(roomId, me, reportedId, reason, request.reasonDetail()));

    return new ChatReportResponse(saved.getId(), roomId, saved.getReason(), saved.getCreatedAt());
  }
}
