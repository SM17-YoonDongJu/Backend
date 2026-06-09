package com.soma.backend.domain.chat.dto;

import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * STOMP /app/chat.send 페이로드.
 * sender_id는 신뢰하지 않고 Principal에서 추출하므로 포함하지 않는다.
 */
@Getter
@NoArgsConstructor
public class ChatSendRequest {

    private UUID roomId;
    private String content;
}
