package com.soma.backend.global.security;

import java.security.Principal;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * STOMP SUBSCRIBE 인가. {@code /topic/chat.rooms.{roomId}} 구독 시 세션 Principal(userId)이 해당 방의
 * 참여자인지 검증하고, 아니면 구독을 거부한다(설계서 §5). 클라이언트는 구독만 하므로 SEND는 다루지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatSubscribeInterceptor implements ChannelInterceptor {

  private static final String ROOM_DESTINATION_PREFIX = "/topic/chat.rooms.";

  private final ChatRoomRepository chatRoomRepository;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      return message;
    }
    String destination = accessor.getDestination();
    if (destination == null || !destination.startsWith(ROOM_DESTINATION_PREFIX)) {
      return message;
    }

    Principal user = accessor.getUser();
    if (user == null) {
      throw new BusinessException(ErrorCode.CHAT_WS_UNAUTHORIZED);
    }
    UUID me = parseUuid(user.getName(), ErrorCode.CHAT_WS_UNAUTHORIZED);
    UUID roomId = parseUuid(destination.substring(ROOM_DESTINATION_PREFIX.length()), ErrorCode.CHAT_ROOM_NOT_FOUND);
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
    return message;
  }

  private UUID parseUuid(String value, ErrorCode onError) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(onError);
    }
  }
}
