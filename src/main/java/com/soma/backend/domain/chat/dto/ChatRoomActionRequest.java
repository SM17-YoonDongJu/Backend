package com.soma.backend.domain.chat.dto;

import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * STOMP /app/chat.join, /app/chat.leave 페이로드.
 */
@Getter
@NoArgsConstructor
public class ChatRoomActionRequest {

    private UUID roomId;
}
