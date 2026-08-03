package com.soma.backend.domain.auth.dto;

import java.time.LocalDate;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 소셜 회원가입 요청. 이메일은 수집하지 않는다.
 *
 * <p>이름의 정식 JSON 키는 {@code name}이며, 구 계약 키 {@code nickname}은
 * {@link JsonAlias} 별칭으로만 계속 수용한다(신규 클라이언트는 사용 금지 — deprecated).
 * {@code name}과 {@code nickname}을 <b>동시에 전송하지 말 것</b> — Jackson은 정식 키/별칭이
 * 중복되면 JSON에서 나중에 나온 값으로 덮어쓰므로 결과가 필드 순서에 의존한다.
 *
 * @param provider    소셜 제공자(kakao|naver|apple) — 가입 티켓의 provider와 일치해야 한다
 * @param socialToken 콜백에서 발급된 가입 티켓(short-lived JWT)
 * @param name        이름(실명, 1~30자, users.nickname). 구 키 {@code nickname}도 별칭으로 허용한다
 * @param birthDate   생년월일(과거 날짜)
 * @param phoneNumber 전화번호(하이픈 허용, users.phone_number unique)
 * @param gender      성별(users.gender). 필수 입력이나 빈 문자열은 허용한다(10자 이하).
 * @param region      활동/거주 지역(선택, 100자 이하). 복수 지역은 {@code ·}로 연결한 단일 문자열
 *                    ({@code "서울·경기"}). 미전송·빈 문자열이면 저장하지 않는다(NULL).
 * @param userType    insured_person|adjuster
 */
public record RegisterRequest(
    @NotBlank String provider,
    @NotBlank String socialToken,
    @JsonAlias("nickname") @NotBlank @Size(min = 1, max = 30) String name,
    @NotNull @Past LocalDate birthDate,
    @NotBlank @Pattern(
        regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$",
        message = "유효한 휴대폰 번호 형식이 아닙니다.") String phoneNumber,
    @NotNull @Size(max = 10) String gender,
    @Nullable @Size(max = 100, message = "지역은 100자 이하여야 합니다.") String region,
    @NotBlank String userType) {
}
