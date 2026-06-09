package com.soma.backend.domain.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateRoomRequest(
        @Size(max = 100, message = "title은 100자를 초과할 수 없습니다.")
        String title,

        @NotNull(message = "type은 필수입니다.")
        @Pattern(regexp = "DIRECT|GROUP", message = "type은 DIRECT 또는 GROUP 이어야 합니다.")
        String type,

        @NotNull(message = "memberIds는 필수입니다.")
        List<UUID> memberIds
) {
}
