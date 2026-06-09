package com.soma.backend.domain.chat.dto;

import java.util.List;

public record RoomListResponse(
        List<RoomSummaryResponse> rooms,
        String nextCursor
) {
}
