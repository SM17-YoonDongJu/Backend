package com.soma.backend.domain.auth.dto;

import java.util.UUID;

/** dev 전용 로그인 응답. 발급된 테스트 유저의 식별 정보. */
public record DevLoginResponse(UUID userId, String nickname, String role) {
}
