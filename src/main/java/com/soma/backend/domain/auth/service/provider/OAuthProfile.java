package com.soma.backend.domain.auth.service.provider;

import org.jspecify.annotations.Nullable;

/**
 * 소셜 제공자에서 조회한 최소 프로필. 회원 식별에 필요한 provider·providerUserId와, 탈퇴 시 제공자 토큰
 * revoke에 쓸 {@code providerRefreshToken}(Apple만 제공, 그 외 provider는 {@code null})을 담는다.
 *
 * <p>kakao/naver 전략과 기존 호출부는 refresh_token 개념이 없으므로 2-arg 위임 생성자로 무변경 유지된다
 * (refresh는 {@code null}). Apple 전략만 3-arg를 사용한다. {@code providerRefreshToken}은 <b>평문</b>이라
 * 로깅하지 말고, 저장 전 반드시 암호화한다.
 */
public record OAuthProfile(String provider, String providerUserId,
    @Nullable String providerRefreshToken) {

  /**
   * refresh_token이 없는 provider(kakao·naver 등)용 위임 생성자. {@code providerRefreshToken=null}.
   */
  public OAuthProfile(String provider, String providerUserId) {
    this(provider, providerUserId, null);
  }

  /**
   * providerRefreshToken(평문 credential)이 로깅·예외 메시지에 노출되지 않도록 마스킹한다.
   */
  @Override
  public String toString() {
    return "OAuthProfile[provider=" + provider + ", providerUserId=" + providerUserId
        + ", providerRefreshToken=" + (providerRefreshToken == null ? "null" : "***") + "]";
  }
}
