package com.soma.backend.domain.auth.service.provider;

/**
 * 소셜 제공자별 인가코드 교환 + 프로필 조회 전략. provider별로 이 인터페이스를 구현하고
 * {@link OAuthClientRouter}가 {@link #provider()} 키로 라우팅한다.
 */
public interface OAuthProviderStrategy {

  /**
   * 이 전략이 담당하는 제공자 식별자(라우팅 키). 예: {@code "kakao"}, {@code "naver"}, {@code "apple"}.
   */
  String provider();

  /**
   * 인가코드로 토큰을 교환하고 사용자 프로필을 조회한다.
   *
   * @param code        제공자 인가코드
   * @param state       CSRF 방지용 state(제공자에 따라 미사용, nullable)
   * @param redirectUri 프론트가 인가요청에 사용한 redirect_uri(허용목록 검증 후 토큰 교환에 사용, nullable)
   * @return 정규화된 프로필
   */
  OAuthProfile fetchProfile(String code, String state, String redirectUri);
}
