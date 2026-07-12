package com.soma.backend.domain.auth.dto;

import java.util.UUID;

/**
 * 회원가입 결과. 쿠키(access/refresh)는 응답 헤더로 부착되고 바디에는 노출하지 않는다.
 */
public record RegisterResponse(UUID userId, String nickname, String role) {
}
