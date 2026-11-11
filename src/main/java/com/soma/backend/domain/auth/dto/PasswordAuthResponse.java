package com.soma.backend.domain.auth.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 아이디·비밀번호 가입·로그인 결과. 토큰은 바디에 담지 않는다 — access/refresh는 HttpOnly 쿠키로만
 * 전달된다(기존 인증 규약과 동일).
 *
 * <p>{@code RegisterResponse}(소셜 가입)와 지금은 모양이 같지만 재사용하지 않는다. 소셜 가입 계약과
 * 독립적으로 바뀔 수 있고, 이 기능은 통째로 걷어낼 수 있어야 하기 때문이다.
 *
 * @param userId   회원 식별자
 * @param nickname 이름(실명, {@code users.nickname})
 * @param role     권한(USER 등). 로그인은 가입 시점 값이 아니라 DB의 현재 role을 싣는다
 */
public record PasswordAuthResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nickname,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role) {
}
