package com.soma.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 아이디·비밀번호 로그인 요청. 형식 검증은 {@code @NotBlank}까지만 건다 — 가입 규칙(길이·허용문자)이
 * 나중에 조여지더라도 그 규칙 이전에 만든 계정이 로그인하지 못하는 일을 막기 위해서다. 자격증명이 맞지
 * 않으면 어떤 경우든 같은 401({@code LOGIN_FAILED})로 응답한다.
 *
 * @param loginId  로그인 아이디. 서비스가 소문자로 정규화해 조회한다
 * @param password 비밀번호(평문). 로그·응답에 남기지 않는다
 */
public record PasswordLoginRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "로그인 아이디(대소문자 무관)")
    @NotBlank String loginId,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password", description = "비밀번호")
    @NotBlank String password) {
}
