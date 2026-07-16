package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ChatMessageListResponse;
import com.soma.backend.domain.chat.dto.ChatMessageResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.ChatAttachmentUploader;

/** 채팅 이력 커서 조회(설계서 §4 ②) 단위 테스트. 인가·size 클램프·커서 인코딩·첨부 presigned 치환을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ChatMessageQueryServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private ChatMessageRepository chatMessageRepository;
  @Mock
  private ChatAttachmentUploader chatAttachmentUploader;

  @InjectMocks
  private ChatMessageQueryService service;

  private UUID userId;
  private UUID adjusterId;
  private UUID roomId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    adjusterId = UUID.randomUUID();
    roomId = UUID.randomUUID();
  }

  private ChatRoom activeRoom() {
    return ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.ACTIVE);
  }

  private ChatMessage textMessage(UUID id, UUID senderId, String content, LocalDateTime createdAt) {
    ChatMessage message = ChatMessage.text(roomId, senderId, content);
    ReflectionTestUtils.setField(message, "id", id);
    ReflectionTestUtils.setField(message, "createdAt", createdAt);
    return message;
  }

  @Test
  @DisplayName("참여자가 아니면 CHAT_NOT_A_MEMBER(403)")
  void getMessages_notAMember_throws403() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));

    assertThatThrownBy(() -> service.getMessages(UUID.randomUUID(), roomId, null, 30))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER));
  }

  @Test
  @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND(404)")
  void getMessages_roomNotFound_throws404() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMessages(userId, roomId, null, 30))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
  }

  @Test
  @DisplayName("size<=0이면 기본값 30으로 클램프되어 조회 limit은 31(size+1)이다")
  void getMessages_sizeZeroOrLess_defaultsTo30() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    given(chatMessageRepository.findByCursor(any(), any(), any(), anyInt())).willReturn(List.of());

    service.getMessages(userId, roomId, null, 0);

    verify(chatMessageRepository).findByCursor(eq(roomId), eq(null), eq(null), eq(31));
  }

  @Test
  @DisplayName("size가 100을 초과하면 최대 100으로 클램프되어 조회 limit은 101이다")
  void getMessages_sizeOver100_clampsTo100() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    given(chatMessageRepository.findByCursor(any(), any(), any(), anyInt())).willReturn(List.of());

    service.getMessages(userId, roomId, null, 500);

    verify(chatMessageRepository).findByCursor(eq(roomId), eq(null), eq(null), eq(101));
  }

  @Test
  @DisplayName("has_next=true면 next_cursor를 반환하고, 그 커서로 다음 조회 시 정확히 디코딩되어 전달된다")
  void getMessages_cursorRoundtrips_toNextPageQuery() {
    LocalDateTime t1 = LocalDateTime.of(2026, 7, 16, 10, 0, 1);
    LocalDateTime t2 = LocalDateTime.of(2026, 7, 16, 10, 0, 2);
    ChatMessage first = textMessage(UUID.randomUUID(), userId, "첫번째", t2);
    ChatMessage second = textMessage(UUID.randomUUID(), adjusterId, "두번째(커서 경계)", t1);
    ChatMessage third = textMessage(UUID.randomUUID(), userId, "다음 페이지에만 있어야 함", t1.minusSeconds(1));

    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    given(chatMessageRepository.findByCursor(eq(roomId), eq(null), eq(null), eq(3)))
        .willReturn(List.of(first, second, third));

    ChatMessageListResponse page1 = service.getMessages(userId, roomId, null, 2);

    assertThat(page1.hasNext()).isTrue();
    assertThat(page1.messages()).hasSize(2);
    assertThat(page1.nextCursor()).isNotBlank();

    given(chatMessageRepository.findByCursor(eq(roomId), eq(t1), eq(second.getId()), eq(3)))
        .willReturn(List.of(third));

    ChatMessageListResponse page2 = service.getMessages(userId, roomId, page1.nextCursor(), 2);

    assertThat(page2.hasNext()).isFalse();
    assertThat(page2.messages()).extracting(ChatMessageResponse::content).containsExactly("다음 페이지에만 있어야 함");

    ArgumentCaptor<LocalDateTime> cursorCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(chatMessageRepository).findByCursor(eq(roomId), cursorCaptor.capture(), eq(second.getId()), eq(3));
    assertThat(cursorCaptor.getValue()).isEqualTo(t1);
  }

  @Test
  @DisplayName("형식이 잘못된 cursor는 INVALID_REQUEST(400)")
  void getMessages_malformedCursor_throws400() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));

    assertThatThrownBy(() -> service.getMessages(userId, roomId, "!!!not-base64!!!", 30))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
  }

  @Test
  @DisplayName("첨부 메시지는 presigned GET URL로 치환되고, is_mine은 sender_id==me로 판정된다")
  void getMessages_attachmentMessage_getsPresignedUrlAndIsMineFlag() {
    ChatMessage attachment = ChatMessage.attachment(
        roomId, adjusterId, ChatMessageType.IMAGE, "chat/" + roomId + "/uuid_photo.png",
        "photo.png", "image/png", 1234L, null);
    ReflectionTestUtils.setField(attachment, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(attachment, "createdAt", LocalDateTime.now());

    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(activeRoom()));
    given(chatMessageRepository.findByCursor(any(), any(), any(), anyInt())).willReturn(List.of(attachment));
    given(chatAttachmentUploader.presignedGetUrl(attachment.getAttachmentKey()))
        .willReturn("https://s3.example/presigned-url");

    ChatMessageListResponse response = service.getMessages(userId, roomId, null, 30);

    ChatMessageResponse messageResponse = response.messages().get(0);
    assertThat(messageResponse.attachment().url()).isEqualTo("https://s3.example/presigned-url");
    assertThat(messageResponse.attachment().name()).isEqualTo("photo.png");
    assertThat(messageResponse.isMine()).isFalse();
  }
}
