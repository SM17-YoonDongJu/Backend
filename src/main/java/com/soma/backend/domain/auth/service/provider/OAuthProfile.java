package com.soma.backend.domain.auth.service.provider;

/**
 * 소셜 제공자에서 조회한 최소 프로필. email/nickname은 제공자 동의 항목에 따라 null일 수 있다.
 */
public record OAuthProfile(String provider, String providerUserId, String email, String nickname) {
}
