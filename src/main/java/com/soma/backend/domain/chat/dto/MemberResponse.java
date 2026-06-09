package com.soma.backend.domain.chat.dto;

import com.soma.backend.domain.chat.entity.ChatRoomMember;
import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID userId,
        Instant joinedAt
) {

    public static MemberResponse from(ChatRoomMember member) {
        return new MemberResponse(member.getUserId(), member.getJoinedAt());
    }
}
