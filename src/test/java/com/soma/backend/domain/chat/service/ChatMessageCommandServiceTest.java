package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.soma.backend.domain.chat.dto.ChatMessageResponse;
import com.soma.backend.domain.chat.dto.SendMessageRequest;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.redis.ChatEventPublisher;
import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;
import com.soma.backend.infra.s3.ChatAttachmentUploader;

/** 채팅 메시지 전송(설계서 §4 ③) 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class ChatMessageCommandServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private ChatMessageRepository chatMessageRepository;
  @Mock
  private ChatAttachmentUploader chatAttachmentUploader;
  @Mock
  private ChatEventPublisher chatEventPublisher;

  @InjectMocks
  private ChatMessageCommandService service;

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

  private void stubSave() {
    given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(inv -> {
      ChatMessage message = inv.getArgument(0);
      ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
      ReflectionTestUtils.setField(message, "createdAt", java.time.LocalDateTime.now());
      return message;
    });
  }

  @Test
  @DisplayName("텍스트 메시지 전송: 저장·미리보기 갱신·afterCommit 발행")
  void send_textMessage_savesAndPublishes() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    stubSave();

    ChatMessageResponse response = service.send(userId, roomId, new SendMessageRequest("안녕하세요", null));

    assertThat(response.content()).isEqualTo("안녕하세요");
    assertThat(response.messageType()).isEqualTo(ChatMessageType.TEXT);
    assertThat(response.isMine()).isTrue();
    assertThat(room.getLastMessage()).isEqualTo("안녕하세요");
    assertThat(room.getLastMessageAt()).isNotNull();

    ArgumentCaptor<ChatBroadcastMessage> captor = ArgumentCaptor.forClass(ChatBroadcastMessage.class);
    verify(chatEventPublisher).publishAfterCommit(captor.capture());
    assertThat(captor.getValue().roomId()).isEqualTo(roomId);
    assertThat(captor.getValue().messageType()).isEqualTo("TEXT");
  }

  @Test
  @DisplayName("첨부 메시지 전송: content_type이 image/*면 IMAGE, 캡션은 content로 저장된다")
  void send_imageAttachment_derivesImageTypeAndCaption() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    given(chatAttachmentUploader.presignedGetUrl(any())).willReturn("https://s3.example/presigned");
    stubSave();

    SendMessageRequest.Attachment attachment =
        new SendMessageRequest.Attachment("chat/" + roomId + "/uuid_photo.png", "photo.png", "image/png", 1234L);
    ChatMessageResponse response =
        service.send(userId, roomId, new SendMessageRequest("캡션입니다", List.of(attachment)));

    assertThat(response.messageType()).isEqualTo(ChatMessageType.IMAGE);
    assertThat(response.content()).isEqualTo("캡션입니다");
    assertThat(response.attachment()).isNotNull();
    assertThat(response.attachment().url()).isEqualTo("https://s3.example/presigned");
  }

  @Test
  @DisplayName("첨부 메시지 전송: content_type이 image가 아니면 FILE로 저장된다")
  void send_nonImageAttachment_derivesFileType() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    given(chatAttachmentUploader.presignedGetUrl(any())).willReturn("https://s3.example/presigned");
    stubSave();

    SendMessageRequest.Attachment attachment =
        new SendMessageRequest.Attachment("chat/" + roomId + "/uuid_doc.pdf", "doc.pdf", "application/pdf", null);
    ChatMessageResponse response = service.send(userId, roomId, new SendMessageRequest(null, List.of(attachment)));

    assertThat(response.messageType()).isEqualTo(ChatMessageType.FILE);
  }

  @Test
  @DisplayName("한 메시지에 첨부 여러 개: 이미지+파일 혼합이면 FILE, 응답 attachment는 첫 번째만 담긴다")
  void send_multipleAttachments_returnsAllAndDerivesFileWhenMixed() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));
    given(chatAttachmentUploader.presignedGetUrl(any())).willReturn("https://s3.example/presigned");
    stubSave();

    SendMessageRequest.Attachment image =
        new SendMessageRequest.Attachment("chat/" + roomId + "/uuid_photo.png", "photo.png", "image/png", 10L);
    SendMessageRequest.Attachment pdf =
        new SendMessageRequest.Attachment("chat/" + roomId + "/uuid_doc.pdf", "doc.pdf", "application/pdf", 20L);
    ChatMessageResponse response =
        service.send(userId, roomId, new SendMessageRequest(null, List.of(image, pdf)));

    assertThat(response.messageType()).isEqualTo(ChatMessageType.FILE);
    assertThat(response.attachment()).isNotNull();
    assertThat(response.attachment().name()).isEqualTo("photo.png");
  }

  @Test
  @DisplayName("첨부 key가 방 접두(chat/{roomId}/)와 다르면 CHAT_ATTACHMENT_KEY_MISMATCH(400)")
  void send_attachmentKeyMismatch_throws400() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    SendMessageRequest.Attachment attachment = new SendMessageRequest.Attachment(
        "chat/" + UUID.randomUUID() + "/uuid_doc.pdf", "doc.pdf", "application/pdf", null);

    assertThatThrownBy(() -> service.send(userId, roomId, new SendMessageRequest(null, List.of(attachment))))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ATTACHMENT_KEY_MISMATCH));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  @DisplayName("content·attachment 둘 다 없으면 CHAT_MESSAGE_EMPTY(400)")
  void send_bothEmpty_throws400() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    assertThatThrownBy(() -> service.send(userId, roomId, new SendMessageRequest(null, null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_MESSAGE_EMPTY));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  @DisplayName("공백 문자열만 있는 content는 빈 것으로 취급되어 CHAT_MESSAGE_EMPTY(400)")
  void send_blankContent_throws400() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    assertThatThrownBy(() -> service.send(userId, roomId, new SendMessageRequest("   ", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_MESSAGE_EMPTY));
  }

  @Test
  @DisplayName("참여자가 아니면 CHAT_NOT_A_MEMBER(403)")
  void send_notAMember_throws403() {
    ChatRoom room = activeRoom();
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    assertThatThrownBy(() -> service.send(UUID.randomUUID(), roomId, new SendMessageRequest("hi", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_A_MEMBER));
  }

  @Test
  @DisplayName("CLOSED 방에는 전송할 수 없다 — CHAT_ROOM_CLOSED(409)")
  void send_closedRoom_throws409() {
    ChatRoom room = ChatRoomFixture.withId(roomId, userId, adjusterId, null, null, ChatRoomStatus.CLOSED);
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.of(room));

    assertThatThrownBy(() -> service.send(userId, roomId, new SendMessageRequest("hi", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_CLOSED));
    verify(chatMessageRepository, never()).save(any());
  }

  @Test
  @DisplayName("방이 없으면 CHAT_ROOM_NOT_FOUND(404)")
  void send_roomNotFound_throws404() {
    given(chatRoomRepository.findById(roomId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.send(userId, roomId, new SendMessageRequest("hi", null)))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND));
  }
}
