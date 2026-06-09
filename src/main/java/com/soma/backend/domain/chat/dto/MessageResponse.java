package com.soma.backend.domain.chat.dto;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID messageId,
        UUID roomId,
        UUID senderId,
        ChatMessageType type,
        String content,
        Instant createdAt
) {

    public static MessageResponse from(ChatMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getRoomId(),
                message.getSenderId(),
                message.getType(),
                message.getContent(),
                message.getCreatedAt()
        );
    }

    /**
     * 입장/퇴장 등 시스템 메시지용 응답을 생성한다(DB 저장 없이 즉석 브로드캐스트).
     * realtime-developer: ChatStompController의 join/leave 시스템 메시지에 사용.
     */
    public static MessageResponse systemMessage(UUID roomId, UUID senderId,
                                                ChatMessageType type, String content) {
        return new MessageResponse(
                UUID.randomUUID(),
                roomId,
                senderId,
                type,
                content,
                Instant.now()
        );
    }
}
