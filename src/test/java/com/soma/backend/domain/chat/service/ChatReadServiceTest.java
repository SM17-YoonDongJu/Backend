package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ReadResponse;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** 읽음 처리(설계서 §4 ⑥) 단위 테스트. 내 읽음 커서만 갱신되고 상대 커서는 불변이어야 한다. */
@ExtendWith(MockitoExtension.class)
class ChatReadServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;

  @InjectMocks
  private ChatReadService service;

  private UUID userId;
  private UUID adjusterId;
  private UUID roomId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    roomId = UUID.randomUUID();
  }

  @Test
  @DisplayName("사용자(user)가 읽으면 user_last_read_at만 갱신되고 adjuster 커서는 불변이다")
  void read_asUser_updatesOnlyUserCursor() {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
    LocalDateTime beforeAdjusterRead = LocalDateTime.of(2026, 1, 1, 0, 0);
    org.springframework.test.util.ReflectionTestUtils.setField(room, "adjusterLastReadAt", beforeAdjusterRead);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    ReadResponse response = service.read(userId, roomId);

    assertThat(room.getUserLastReadAt()).isNotNull();
    assertThat(room.getAdjusterLastReadAt()).isEqualTo(beforeAdjusterRead);
    assertThat(response.chatRoomId()).isEqualTo(roomId);
    assertThat(response.readAt()).isEqualTo(room.getUserLastReadAt());
  }

  @Test
  @DisplayName("사정사(adjuster)가 읽으면 adjuster_last_read_at만 갱신되고 user 커서는 불변이다")
  void read_asAdjuster_updatesOnlyAdjusterCursor() {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    service.read(adjusterId, roomId);

    assertThat(room.getAdjusterLastReadAt()).isNotNull();
    assertThat(room.getUserLastReadAt()).isNull();
  }

  @Test
  @DisplayName("참여자가 아니면 CHAT_NOT_A_MEMBER(403)")
  void read_notAMember_throwsForbidden() {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    assertThatThrownBy(() -> service.read(UUID.randomUUID(), roomId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER));
  }

  @Test
  @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND(404)")
  void read_roomNotFound_throws404() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.read(userId, roomId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
  }
}
