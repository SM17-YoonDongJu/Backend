package com.soma.backend.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 소셜 회원가입 요청.
 *
 * @param provider    소셜 제공자(kakao|naver) — 가입 티켓의 provider와 일치해야 한다
 * @param socialToken 콜백에서 발급된 가입 티켓(short-lived JWT)
 * @param nickname    표시 닉네임(2~20자, users.nickname unique)
 * @param userType    insured_person|adjuster
 * @param email       선택 이메일(티켓 email이 없을 때 폴백)
 */
public record RegisterRequest(
    @NotBlank String provider,
    @NotBlank String socialToken,
    @NotBlank @Size(min = 2, max = 20) String nickname,
    @NotBlank String userType,
    @Email String email) {
}
