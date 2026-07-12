package com.soma.backend.domain.auth.service.provider;

/**
 * 소셜 제공자에서 조회한 최소 프로필. 회원 식별에 필요한 provider·providerUserId만 담는다.
 */
public record OAuthProfile(String provider, String providerUserId) {
}
