package com.soma.backend.domain.chat.dto;

import java.util.List;

public record MessageHistoryResponse(
        List<MessageResponse> messages,
        String nextCursor,
        boolean hasNext
) {
}
