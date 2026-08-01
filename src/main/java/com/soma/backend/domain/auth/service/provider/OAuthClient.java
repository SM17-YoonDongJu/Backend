package com.soma.backend.domain.auth.service.provider;

/**
 * 소셜 제공자에 대한 인가코드 교환 + 프로필 조회 추상화.
 *
 * <p>외부 HTTP 호출을 캡슐화해 도메인 서비스가 제공자 세부(토큰 URI·응답 형태)를 몰라도 되게 하고,
 * 테스트에서 이 인터페이스를 목킹해 외부 의존 없이 로그인/가입 플로우를 검증할 수 있게 한다.
 */
public interface OAuthClient {

  /**
   * 인가코드로 토큰을 교환하고 사용자 프로필을 조회한다.
   *
   * @param provider    kakao|naver|apple (그 외는 400 UNSUPPORTED_PROVIDER)
   * @param code        제공자 인가코드
   * @param state       CSRF 방지용 state(제공자에 따라 미사용, nullable)
   * @param redirectUri 프론트가 인가요청에 사용한 redirect_uri(허용목록 검증 후 토큰 교환에 사용, nullable)
   * @return 정규화된 프로필
   */
  OAuthProfile fetchProfile(String provider, String code, String state, String redirectUri);
}
