package com.soma.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 아이디·비밀번호 회원가입 요청. 소셜 가입과 달리 생년월일·성별·전화번호를 받지 않는다
 * (받지 않은 NOT NULL 컬럼은 {@code User.createWithoutProfile}이 '미상' 센티넬로 채운다).
 *
 * @param loginId  로그인 아이디(영문·숫자·밑줄 4~20자). 서비스가 소문자로 정규화해 저장·조회하므로
 *                 {@code Hong}과 {@code hong}은 같은 아이디다
 * @param password 비밀번호(8~72자 + UTF-8 72바이트 이내). 상한 72는 BCrypt가 키를 72바이트까지만 쓰기
 *                 때문이다. {@code @Size}는 <b>문자 수</b>만 세므로 바이트 상한은 이 애노테이션으로 표현할
 *                 수 없다 — 한글은 글자당 3바이트라 25자면 75바이트가 되고, Spring Security 7의 BCrypt는
 *                 그 입력을 자르지 않고 예외로 거부한다. 그래서 바이트 한도는 {@code PasswordSignupService}가
 *                 해싱 전에 확인해 400으로 돌려준다(여기서 빼면 같은 입력이 500이 된다)
 * @param name     이름(실명, 1~30자). {@code users.nickname}에 저장된다
 */
public record PasswordSignupRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "로그인 아이디(영문·숫자·밑줄 4~20자). 대소문자를 구분하지 않는다.")
    @NotBlank
    @Pattern(
        regexp = "^[A-Za-z0-9_]{4,20}$",
        message = "아이디는 영문·숫자·밑줄 4~20자여야 합니다.") String loginId,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password",
        description = "비밀번호(8~72자, UTF-8 기준 72바이트 이내 — 한글은 글자당 3바이트). "
            + "응답·로그에 다시 노출되지 않는다.")
    @NotBlank
    @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다.") String password,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "이름(실명, 1~30자)")
    @NotBlank
    @Size(min = 1, max = 30) String name) {
}
