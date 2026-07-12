package com.soma.backend.infra.outbox;

/**
 * 소셜 신원(제공자 + 제공자 측 사용자 ID). 탈퇴 후처리에서 탈퇴 원장 기록에 사용한다. 개인정보가 아닌
 * 불투명 식별자만 담는다.
 */
public record SocialIdentity(String provider, String providerUserId) {
}
