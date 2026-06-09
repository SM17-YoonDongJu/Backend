package com.soma.backend.domain.chat.dto;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoomResponse(
        UUID roomId,
        String title,
        ChatRoomType type,
        List<MemberResponse> members,
        MessageResponse lastMessage,
        Instant createdAt
) {

    public static RoomResponse of(ChatRoom room, List<MemberResponse> members, ChatMessage lastMessage) {
        return new RoomResponse(
                room.getId(),
                room.getTitle(),
                room.getType(),
                members,
                lastMessage == null ? null : MessageResponse.from(lastMessage),
                room.getCreatedAt()
        );
    }
}
