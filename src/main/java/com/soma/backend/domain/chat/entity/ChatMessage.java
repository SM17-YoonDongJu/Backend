package com.soma.backend.domain.chat.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.util.StringUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 상담 채팅 메시지(불변, append-only). TEXT는 content 필수, 첨부(IMAGE/FILE)는 attachments(1개 이상) 필수,
 * SYSTEM은 발신자 없이 서버가 생성하는 안내 메시지다. 첨부는 private S3 객체 key만 attachments(jsonb 배열)에
 * 저장하고 조회 시 presigned GET URL로 변환한다(엔티티는 URL을 알지 못한다). 한 메시지에 여러 첨부를 담을 수 있다.
 */
@Entity
@Table(name = "chatroom_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatMessage {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(name = "sender_id")
  private UUID senderId;

  @Enumerated(EnumType.STRING)
  @Column(name = "message_type", nullable = false, length = 20)
  private ChatMessageType messageType;

  @Column(name = "content")
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attachments", columnDefinition = "jsonb")
  private List<ChatAttachment> attachments;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  private ChatMessage(UUID roomId, UUID senderId, ChatMessageType messageType, String content,
      List<ChatAttachment> attachments) {
    this.roomId = roomId;
    this.senderId = senderId;
    this.messageType = messageType;
    this.content = content;
    this.attachments = attachments;
  }

  /** 텍스트 메시지. content 공백이면 CHAT_MESSAGE_EMPTY. */
  public static ChatMessage text(UUID roomId, UUID senderId, String content) {
    if (!StringUtils.hasText(content)) {
      throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
    }
    return new ChatMessage(roomId, senderId, ChatMessageType.TEXT, content, null);
  }

  /** 첨부 메시지(이미지/파일). 첨부 1개 이상 필수, content는 캡션(nullable). */
  public static ChatMessage attachment(UUID roomId, UUID senderId, ChatMessageType type,
      List<ChatAttachment> attachments, String caption) {
    if (attachments == null || attachments.isEmpty()) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_EMPTY);
    }
    return new ChatMessage(roomId, senderId, type, caption, List.copyOf(attachments));
  }

  /** 시스템 안내 메시지(상담 시작·종결 등). 발신자 없음. */
  public static ChatMessage system(UUID roomId, String content) {
    return new ChatMessage(roomId, null, ChatMessageType.SYSTEM, content, null);
  }

  public boolean isMine(UUID accountId) {
    return accountId != null && accountId.equals(senderId);
  }

  public boolean hasAttachment() {
    return attachments != null && !attachments.isEmpty();
  }
}
