package com.soma.backend.infra.outbox;

/**
 * {@code APPLE_REVOKE} 아웃박스 이벤트 페이로드. 탈퇴한 회원의 Apple refresh_token <b>암호문</b>과
 * 복호화 AAD 재구성용 행 식별자(provider, providerUserId)를 담는다. revoke 호출 직전에만 복호화하므로
 * 아웃박스 payload에도 평문은 남지 않는다.
 *
 * <p>AAD 도입(#225) 전에 적재된 이벤트는 {@code provider}·{@code providerUserId}가 {@code null}로
 * 역직렬화되는데, 그 암호문은 어차피 구버전(AAD 없음)이라 legacy 경로로 복호화된다.
 */
public record AppleRevokePayload(String encryptedRefreshToken, String provider, String providerUserId) {
}
