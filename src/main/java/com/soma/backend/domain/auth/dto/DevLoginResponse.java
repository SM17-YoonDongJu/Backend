package com.soma.backend.domain.auth.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** dev 전용 로그인 응답. 발급된 테스트 유저의 식별 정보. */
public record DevLoginResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nickname,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role) {
}
