package com.soma.backend.domain.auth.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 소셜 회원가입 요청. 이메일은 수집하지 않는다.
 *
 * @param provider    소셜 제공자(kakao|naver) — 가입 티켓의 provider와 일치해야 한다
 * @param socialToken 콜백에서 발급된 가입 티켓(short-lived JWT)
 * @param nickname    이름(실명, 1~30자, users.nickname)
 * @param birthDate   생년월일(과거 날짜)
 * @param phoneNumber 전화번호(하이픈 허용, users.phone_number unique)
 * @param userType    insured_person|adjuster
 */
public record RegisterRequest(
    @NotBlank String provider,
    @NotBlank String socialToken,
    @NotBlank @Size(min = 1, max = 30) String nickname,
    @NotNull @Past LocalDate birthDate,
    @NotBlank @Pattern(
        regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$",
        message = "유효한 휴대폰 번호 형식이 아닙니다.") String phoneNumber,
    @NotBlank String userType) {
}
