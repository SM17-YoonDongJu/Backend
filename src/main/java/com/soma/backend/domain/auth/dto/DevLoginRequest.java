package com.soma.backend.domain.auth.dto;

import org.jspecify.annotations.Nullable;

/** dev 전용 로그인 요청. nickname을 주면 그 유저로, 없으면 기본 테스트 유저로 로그인한다. */
public record DevLoginRequest(@Nullable String nickname) {
}
