package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.soma.backend.domain.chat.entity.ChatMessageType;

/**
 * 채팅 메시지 응답(설계서 §4 ②·③ 공통 DTO). 이력(②)은 {@code isMine}을, 전송(③)은 {@code chatRoomId}를
 * 사용하며 두 엔드포인트가 이 레코드를 공유한다. {@code attachments[].url}은 조회 시점의 단기 presigned GET URL이며,
 * 첨부 없는 메시지는 빈 배열이다.
 */
public record ChatMessageResponse(
    UUID messageId,
    UUID chatRoomId,
    UUID senderId,
    ChatMessageType messageType,
    String content,
    List<Attachment> attachments,
    boolean isMine,
    LocalDateTime createdAt) {

  /** 첨부 표시 정보 + 단기 presigned GET URL. */
  public record Attachment(String url, String name, String contentType, Long size) {
  }
}
