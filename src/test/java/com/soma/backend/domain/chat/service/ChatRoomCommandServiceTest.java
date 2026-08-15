package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.infra.redis.ChatEventPublisher;
import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;

/**
 * 상담 채팅방 개설(design.md §8-6 #10) 단위 테스트. 제안 1건당 방 1개 멱등 확보와, 방이 실제로 새로 열릴
 * 때만 따라붙는 부수효과(SYSTEM 안내 메시지 저장 · 커밋 후 브로드캐스트)를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomCommandService 상담 방 개설")
class ChatRoomCommandServiceTest {

  @Mock
  private ChatRoomRepository chatRoomRepository;
  @Mock
  private ChatMessageRepository chatMessageRepository;
  @Mock
  private ChatEventPublisher chatEventPublisher;
  @InjectMocks
  private ChatRoomCommandService service;

  private final UUID userId = UUID.randomUUID();
  private final UUID adjusterId = UUID.randomUUID();
  private final UUID reportId = UUID.randomUUID();
  private final UUID reportReviewId = UUID.randomUUID();
  private final UUID roomId = UUID.randomUUID();
  private final UUID messageId = UUID.randomUUID();
  private final LocalDateTime sentAt = LocalDateTime.of(2026, 8, 15, 10, 0);

  private void givenRoomAndMessageAreSaved() {
    given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(inv -> {
      ChatRoom saved = inv.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", roomId);
      return saved;
    });
    given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(inv -> {
      ChatMessage saved = inv.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", messageId);
      ReflectionTestUtils.setField(saved, "createdAt", sentAt);
      return saved;
    });
  }

  @Test
  @DisplayName("제안에 연결된 방이 없으면 새로 개설하고 created=true를 돌려준다")
  void opensNewRoomWhenNoneExists() {
    // Given
    given(chatRoomRepository.findByReportReviewId(reportReviewId)).willReturn(Optional.empty());
    givenRoomAndMessageAreSaved();

    // When
    ConsultationRoomResult result =
        service.openConsultationRoom(userId, adjusterId, reportId, reportReviewId);

    // Then
    assertThat(result.created()).isTrue();
    assertThat(result.chatRoomId()).isEqualTo(roomId);

    ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
    verify(chatRoomRepository).save(roomCaptor.capture());
    ChatRoom opened = roomCaptor.getValue();
    assertThat(opened.getUserId()).isEqualTo(userId);
    assertThat(opened.getAdjusterId()).isEqualTo(adjusterId);
    assertThat(opened.getReportId()).isEqualTo(reportId);
    assertThat(opened.getReportReviewId()).isEqualTo(reportReviewId);
    assertThat(opened.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
  }

  @Test
  @DisplayName("신규 개설이면 SYSTEM 안내 메시지 1건을 저장하고 방 미리보기를 갱신한다")
  void opensNewRoomWithSystemMessage() {
    // Given
    given(chatRoomRepository.findByReportReviewId(reportReviewId)).willReturn(Optional.empty());
    givenRoomAndMessageAreSaved();

    // When
    service.openConsultationRoom(userId, adjusterId, reportId, reportReviewId);

    // Then
    ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
    verify(chatMessageRepository).save(messageCaptor.capture());
    ChatMessage system = messageCaptor.getValue();
    assertThat(system.getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
    assertThat(system.getSenderId()).isNull();
    assertThat(system.getRoomId()).isEqualTo(roomId);
    assertThat(system.getContent()).isNotBlank();

    ArgumentCaptor<ChatRoom> roomCaptor = ArgumentCaptor.forClass(ChatRoom.class);
    verify(chatRoomRepository).save(roomCaptor.capture());
    assertThat(roomCaptor.getValue().getLastMessage()).isEqualTo(system.getContent());
    assertThat(roomCaptor.getValue().getLastMessageAt()).isEqualTo(sentAt);
  }

  @Test
  @DisplayName("신규 개설이면 커밋 후 브로드캐스트(SYSTEM)를 1회 예약한다")
  void opensNewRoomAndPublishesBroadcastAfterCommit() {
    // Given
    given(chatRoomRepository.findByReportReviewId(reportReviewId)).willReturn(Optional.empty());
    givenRoomAndMessageAreSaved();

    // When
    service.openConsultationRoom(userId, adjusterId, reportId, reportReviewId);

    // Then
    ArgumentCaptor<ChatBroadcastMessage> broadcastCaptor =
        ArgumentCaptor.forClass(ChatBroadcastMessage.class);
    verify(chatEventPublisher).publishAfterCommit(broadcastCaptor.capture());
    ChatBroadcastMessage broadcast = broadcastCaptor.getValue();
    assertThat(broadcast.roomId()).isEqualTo(roomId);
    assertThat(broadcast.messageId()).isEqualTo(messageId);
    assertThat(broadcast.senderId()).isNull();
    assertThat(broadcast.messageType()).isEqualTo(ChatMessageType.SYSTEM.name());
    assertThat(broadcast.content()).isNotBlank();
    assertThat(broadcast.attachments()).isEmpty();
    assertThat(broadcast.createdAt()).isEqualTo(sentAt);
  }

  @Test
  @DisplayName("제안에 연결된 방이 이미 있으면 재사용하고 created=false·부수효과 0건이다(더블클릭 멱등)")
  void reusesExistingRoomWithoutSideEffects() {
    // Given
    ChatRoom existing = ChatRoomFixture.withId(
        roomId, userId, adjusterId, reportId, reportReviewId, ChatRoomStatus.ACTIVE);
    given(chatRoomRepository.findByReportReviewId(reportReviewId)).willReturn(Optional.of(existing));

    // When
    ConsultationRoomResult result =
        service.openConsultationRoom(userId, adjusterId, reportId, reportReviewId);

    // Then
    assertThat(result.created()).isFalse();
    assertThat(result.chatRoomId()).isEqualTo(roomId);
    verify(chatRoomRepository, never()).save(any(ChatRoom.class));
    verify(chatMessageRepository, never()).save(any(ChatMessage.class));
    verify(chatEventPublisher, never()).publishAfterCommit(any(ChatBroadcastMessage.class));
  }

  /**
   * 방어적 계약 확인. 현재 플로우에서 CLOSED 방은 항상 제안이 REJECTED인 상태라(형제 방 종료·상담 거절이
   * 둘 다 제안을 종료시킨다) 호출자인 report 쪽 {@code ensureDecidable}에서 이미 409로 막힌다. 그럼에도
   * 이 서비스 단독 계약은 "제안당 방 1개"이므로, 방 status와 무관하게 재개설하지 않아야 한다.
   */
  @Test
  @DisplayName("이미 종료(CLOSED)된 방이 있어도 재개설하지 않고 그대로 재사용한다")
  void reusesClosedRoomWithoutReopening() {
    // Given
    ChatRoom closed = ChatRoomFixture.withId(
        roomId, userId, adjusterId, reportId, reportReviewId, ChatRoomStatus.CLOSED);
    given(chatRoomRepository.findByReportReviewId(reportReviewId)).willReturn(Optional.of(closed));

    // When
    ConsultationRoomResult result =
        service.openConsultationRoom(userId, adjusterId, reportId, reportReviewId);

    // Then
    assertThat(result.created()).isFalse();
    assertThat(result.chatRoomId()).isEqualTo(roomId);
    verify(chatRoomRepository, never()).save(any(ChatRoom.class));
  }
}
