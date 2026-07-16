package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatMessageType;

/**
 * 채팅 메시지 응답(설계서 §4 ②·③ 공통 DTO). 이력(②)은 {@code isMine}을, 전송(③)은 {@code chatRoomId}를
 * 사용하며 두 엔드포인트가 이 레코드를 공유한다. {@code attachment.url}은 조회 시점의 단기 presigned GET URL.
 */
public record ChatMessageResponse(
    UUID messageId,
    UUID chatRoomId,
    UUID senderId,
    ChatMessageType messageType,
    String content,
    Attachment attachment,
    boolean isMine,
    LocalDateTime createdAt) {

  /** 첨부 표시 정보 + 단기 presigned GET URL. */
  public record Attachment(String url, String name, String contentType) {
  }
}
