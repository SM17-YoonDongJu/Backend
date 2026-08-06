package com.soma.backend.domain.auth.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회원가입 결과. 쿠키(access/refresh)는 응답 헤더로 부착되고 바디에는 노출하지 않는다.
 */
public record RegisterResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nickname,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role) {
}
