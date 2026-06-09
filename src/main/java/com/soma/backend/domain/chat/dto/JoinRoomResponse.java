package com.soma.backend.domain.chat.dto;

import com.soma.backend.domain.chat.entity.ChatRoomMember;
import java.time.Instant;
import java.util.UUID;

public record JoinRoomResponse(
        UUID roomId,
        UUID userId,
        Instant joinedAt
) {

    public static JoinRoomResponse from(ChatRoomMember member) {
        return new JoinRoomResponse(
                member.getChatRoom().getId(),
                member.getUserId(),
                member.getJoinedAt()
        );
    }
}
