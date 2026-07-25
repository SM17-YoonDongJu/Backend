package com.soma.backend.domain.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatMessageResponse;
import com.soma.backend.domain.chat.dto.SendMessageRequest;
import com.soma.backend.domain.chat.entity.ChatAttachment;
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

/**
 * 채팅 메시지 전송(설계서 §4 ③). 저장 + last_message 비정규화 후 커밋되면 Redis relay로 브로드캐스트한다
 * (afterCommit — 롤백 시 미발행). 첨부는 저장된 key만 참조하며 응답·브로드캐스트에는 단기 presigned URL을 담는다.
 * 한 메시지에 여러 첨부를 담을 수 있고, 모두 이미지면 IMAGE·하나라도 비이미지면 FILE로 분류한다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

  private static final int PREVIEW_MAX = 100;
  private static final int MAX_ATTACHMENTS = 10;
  private static final String ATTACHMENT_PREVIEW = "[첨부]";

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatAttachmentUploader chatAttachmentUploader;
  private final ChatEventPublisher chatEventPublisher;

  @Transactional
  public ChatMessageResponse send(UUID me, UUID roomId, SendMessageRequest request) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
    if (room.getStatus() == ChatRoomStatus.CLOSED) {
      throw new BusinessException(ErrorCode.CHAT_ROOM_CLOSED);
    }

    boolean hasAttachment = request.attachments() != null && !request.attachments().isEmpty();
    boolean hasContent = StringUtils.hasText(request.content());
    if (!hasAttachment && !hasContent) {
      throw new BusinessException(ErrorCode.CHAT_MESSAGE_EMPTY);
    }

    ChatMessage message = hasAttachment
        ? buildAttachmentMessage(me, roomId, request)
        : ChatMessage.text(roomId, me, request.content());
    ChatMessage saved = chatMessageRepository.save(message);
    room.touchLastMessage(buildPreview(saved), saved.getCreatedAt());

    chatEventPublisher.publishAfterCommit(toBroadcast(saved));
    return toResponse(saved, roomId, me);
  }

  private ChatMessage buildAttachmentMessage(UUID me, UUID roomId, SendMessageRequest request) {
    List<SendMessageRequest.Attachment> requested = request.attachments();
    if (requested.size() > MAX_ATTACHMENTS) {
      throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_TOO_MANY);
    }
    String keyPrefix = "chat/" + roomId + "/";
    List<ChatAttachment> attachments = new ArrayList<>(requested.size());
    boolean allImages = true;
    for (SendMessageRequest.Attachment item : requested) {
      if (!StringUtils.hasText(item.attachmentKey())
          || !StringUtils.hasText(item.name())
          || !StringUtils.hasText(item.contentType())) {
        throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_EMPTY);
      }
      if (!item.attachmentKey().startsWith(keyPrefix)) {
        throw new BusinessException(ErrorCode.CHAT_ATTACHMENT_KEY_MISMATCH);
      }
      if (!isImage(item.contentType())) {
        allImages = false;
      }
      attachments.add(new ChatAttachment(item.attachmentKey(), item.name(), item.contentType(), item.size()));
    }
    ChatMessageType type = allImages ? ChatMessageType.IMAGE : ChatMessageType.FILE;
    String caption = StringUtils.hasText(request.content()) ? request.content() : null;
    return ChatMessage.attachment(roomId, me, type, attachments, caption);
  }

  private boolean isImage(String contentType) {
    return contentType != null && contentType.startsWith("image/");
  }

  private String buildPreview(ChatMessage message) {
    if (message.hasAttachment() && !StringUtils.hasText(message.getContent())) {
      return ATTACHMENT_PREVIEW;
    }
    String content = message.getContent();
    if (content == null) {
      return null;
    }
    return content.length() > PREVIEW_MAX ? content.substring(0, PREVIEW_MAX) : content;
  }

  private ChatMessageResponse toResponse(ChatMessage message, UUID roomId, UUID me) {
    return new ChatMessageResponse(
        message.getId(),
        roomId,
        message.getSenderId(),
        message.getMessageType(),
        message.getContent(),
        toResponseAttachment(message),
        message.isMine(me),
        message.getCreatedAt());
  }

  private ChatMessageResponse.Attachment toResponseAttachment(ChatMessage message) {
    if (!message.hasAttachment()) {
      return null;
    }
    ChatAttachment attachment = message.getAttachments().get(0);
    return new ChatMessageResponse.Attachment(
        chatAttachmentUploader.presignedGetUrl(attachment.attachmentKey()),
        attachment.name(),
        attachment.contentType(),
        attachment.size());
  }

  private ChatBroadcastMessage toBroadcast(ChatMessage message) {
    return new ChatBroadcastMessage(
        message.getRoomId(),
        message.getId(),
        message.getSenderId(),
        message.getMessageType().name(),
        message.getContent(),
        toBroadcastAttachments(message),
        message.getCreatedAt());
  }

  private List<ChatBroadcastMessage.Attachment> toBroadcastAttachments(ChatMessage message) {
    if (!message.hasAttachment()) {
      return List.of();
    }
    return message.getAttachments().stream()
        .map(attachment -> new ChatBroadcastMessage.Attachment(
            chatAttachmentUploader.presignedGetUrl(attachment.attachmentKey()),
            attachment.name(),
            attachment.contentType(),
            attachment.size()))
        .toList();
  }
}
