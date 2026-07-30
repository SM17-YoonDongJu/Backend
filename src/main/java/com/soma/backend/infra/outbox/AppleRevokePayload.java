package com.soma.backend.infra.outbox;

/**
 * {@code APPLE_REVOKE} 아웃박스 이벤트 페이로드. 탈퇴한 회원의 Apple refresh_token <b>암호문</b>을 담는다.
 * revoke 호출 직전에만 복호화하므로 아웃박스 payload에도 평문은 남지 않는다.
 */
public record AppleRevokePayload(String encryptedRefreshToken) {
}
