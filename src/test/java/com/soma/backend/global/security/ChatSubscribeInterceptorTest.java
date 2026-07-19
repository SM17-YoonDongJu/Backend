package com.soma.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * STOMP SUBSCRIBE 인가(설계서 §5, WS: "비참여자 구독 거부") 단위 테스트. {@code /topic/chat.rooms.{roomId}}
 * 구독 시 세션 Principal이 해당 방의 참여자인지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatSubscribeInterceptorTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private MessageChannel messageChannel;

  @InjectMocks
  private ChatSubscribeInterceptor interceptor;

  private UUID userId;
  private UUID adjusterId;
  private UUID roomId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    roomId = UUID.randomUUID();
  }

  private Message<byte[]> subscribeMessage(String destination, Principal user) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    if (user != null) {
      accessor.setUser(user);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private Principal principalOf(UUID id) {
    return id::toString;
  }

  @Test
  @DisplayName("SUBSCRIBE가 아닌 커맨드는 그대로 통과한다(인가 검사 없음)")
  void preSend_nonSubscribeCommand_passesThrough() {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination("/topic/chat.rooms." + roomId);
    Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

    Message<?> result = interceptor.preSend(message, messageChannel);

    assertThat(result).isSameAs(message);
  }

  @Test
  @DisplayName("채팅방 구독이 아닌 다른 목적지 SUBSCRIBE는 그대로 통과한다")
  void preSend_nonChatRoomDestination_passesThrough() {
    Message<byte[]> message = subscribeMessage("/topic/other.topic", principalOf(userId));

    Message<?> result = interceptor.preSend(message, messageChannel);

    assertThat(result).isSameAs(message);
  }

  @Test
  @DisplayName("Principal(인증 정보)이 없으면 CHAT_WS_UNAUTHORIZED")
  void preSend_noPrincipal_throwsUnauthorized() {
    Message<byte[]> message = subscribeMessage("/topic/chat.rooms." + roomId, null);

    assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_WS_UNAUTHORIZED));
  }

  @Test
  @DisplayName("방 참여자면 구독을 허용한다")
  void preSend_member_allowsSubscription() {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    Message<byte[]> message = subscribeMessage("/topic/chat.rooms." + roomId, principalOf(userId));

    Message<?> result = interceptor.preSend(message, messageChannel);

    assertThat(result).isSameAs(message);
  }

  @Test
  @DisplayName("방 참여자가 아니면 CHAT_NOT_A_MEMBER")
  void preSend_notAMember_throwsForbidden() {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    Message<byte[]> message = subscribeMessage("/topic/chat.rooms." + roomId, principalOf(UUID.randomUUID()));

    assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER));
  }

  @Test
  @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND")
  void preSend_roomNotFound_throwsNotFound() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());
    Message<byte[]> message = subscribeMessage("/topic/chat.rooms." + roomId, principalOf(userId));

    assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
  }

  @Test
  @DisplayName("목적지의 roomId가 UUID 형식이 아니면 CHAT_ROOM_NOT_FOUND")
  void preSend_malformedRoomId_throwsNotFound() {
    Message<byte[]> message = subscribeMessage("/topic/chat.rooms.not-a-uuid", principalOf(userId));

    assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
  }
}
