package com.soma.backend.infra.outbox;

/**
 * Apple 토큰 revoke 포트. {@link OutboxProcessor}(infra)가 domain의 Apple 구현을 직접 참조하지 않도록
 * 하는 경계다(구현체 {@code AppleRevokeClient}가 domain에서 이 포트를 구현).
 *
 * <p>revoke 실패(비2xx·타임아웃 등)는 예외로 던져 아웃박스가 재시도하게 한다. 전달받는 refresh_token은
 * <b>복호화된 평문</b>이므로 구현체는 이를 로깅하지 않는다.
 */
public interface AppleTokenRevoker {

  void revoke(String refreshToken);
}
