package com.soma.backend.domain.chat.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ChatMessageListResponse;
import com.soma.backend.domain.chat.dto.ChatMessageResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.s3.ChatAttachmentUploader;

/** 채팅 이력 커서 조회(설계서 §4 ②). 참여자 인가 후 첨부 key를 단기 presigned URL로 변환해 내려준다. */
@Service
@RequiredArgsConstructor
public class ChatMessageQueryService {

  private static final int DEFAULT_SIZE = 30;
  private static final int MAX_SIZE = 100;

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatAttachmentUploader chatAttachmentUploader;

  @Transactional(readOnly = true)
  public ChatMessageListResponse getMessages(UUID me, UUID roomId, String cursor, int size) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }

    int pageSize = clampSize(size);
    LocalDateTime cursorCreatedAt = null;
    UUID cursorId = null;
    if (StringUtils.hasText(cursor)) {
      Cursor parsed = parseCursor(cursor);
      cursorCreatedAt = parsed.createdAt();
      cursorId = parsed.id();
    }

    List<ChatMessage> rows = chatMessageRepository.findByCursor(roomId, cursorCreatedAt, cursorId, pageSize + 1);
    boolean hasNext = rows.size() > pageSize;
    List<ChatMessage> page = hasNext ? rows.subList(0, pageSize) : rows;

    List<ChatMessageResponse> messages = page.stream()
        .map(message -> toResponse(message, roomId, me))
        .toList();
    String nextCursor = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
    return new ChatMessageListResponse(messages, nextCursor, hasNext);
  }

  private ChatMessageResponse toResponse(ChatMessage message, UUID roomId, UUID me) {
    ChatMessageResponse.Attachment attachment = null;
    if (message.hasAttachment()) {
      attachment = new ChatMessageResponse.Attachment(
          chatAttachmentUploader.presignedGetUrl(message.getAttachmentKey()),
          message.getAttachmentName(),
          message.getAttachmentContentType());
    }
    return new ChatMessageResponse(
        message.getId(),
        roomId,
        message.getSenderId(),
        message.getMessageType(),
        message.getContent(),
        attachment,
        message.isMine(me),
        message.getCreatedAt());
  }

  private int clampSize(int size) {
    if (size <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  private String encodeCursor(ChatMessage message) {
    long epochMillis = message.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
    String raw = epochMillis + "_" + message.getId();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private Cursor parseCursor(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] parts = raw.split("_", 2);
      if (parts.length != 2) {
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
      }
      LocalDateTime createdAt = LocalDateTime.ofInstant(
          Instant.ofEpochMilli(Long.parseLong(parts[0])), ZoneOffset.UTC);
      return new Cursor(createdAt, UUID.fromString(parts[1]));
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
  }

  /** 디코딩된 커서 값(createdAt, id). */
  private record Cursor(LocalDateTime createdAt, UUID id) {
  }
}
