package com.soma.backend.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.chat.entity.ChatMessageType;

/**
 * 채팅 메시지 응답(설계서 §4 ②·③ 공통 DTO). 이력(②)은 {@code isMine}을, 전송(③)은 {@code chatRoomId}를
 * 사용하며 두 엔드포인트가 이 레코드를 공유한다. {@code attachment.url}은 조회 시점의 단기 presigned GET URL이며,
 * 첨부 없는 메시지는 {@code null}이다(프론트 계약 — 단수). SYSTEM 메시지는 발신자가 없어 {@code senderId}가 null이다.
 */
public record ChatMessageResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID messageId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID chatRoomId,
    @Nullable @Schema(nullable = true, description = "SYSTEM 메시지는 발신자가 없어 null") UUID senderId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatMessageType messageType,
    @Schema(nullable = true, description = "첨부 메시지(IMAGE/FILE)에 캡션이 없으면 null") String content,
    @Nullable @Schema(nullable = true, description = "첨부가 없는 메시지는 null") Attachment attachment,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isMine,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime createdAt) {

  /** 첨부 표시 정보 + 단기 presigned GET URL. */
  public record Attachment(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentType,
      @Schema(nullable = true) Integer size) {
  }
}
