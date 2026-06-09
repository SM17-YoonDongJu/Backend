package com.soma.backend.domain.chat.dto;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomType;
import java.util.UUID;

public record RoomSummaryResponse(
        UUID roomId,
        String title,
        ChatRoomType type,
        MessageResponse lastMessage,
        int unreadCount
) {

    public static RoomSummaryResponse of(ChatRoom room, ChatMessage lastMessage) {
        return new RoomSummaryResponse(
                room.getId(),
                room.getTitle(),
                room.getType(),
                lastMessage == null ? null : MessageResponse.from(lastMessage),
                0
        );
    }
}
