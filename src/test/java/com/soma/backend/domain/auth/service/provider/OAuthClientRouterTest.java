package com.soma.backend.domain.auth.service.provider;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * {@link OAuthClientRouter}의 provider 키 라우팅 검증. 스텁 전략으로 kakao/naver 위임과
 * 미지원 provider의 UNSUPPORTED_PROVIDER 매핑을 외부 의존 없이 확인한다.
 */
class OAuthClientRouterTest {

  @Test
  void routesToKakaoStrategy() {
    OAuthClientRouter router = new OAuthClientRouter(
        List.of(stub("kakao", "kid-1"), stub("naver", "nid-1")));

    OAuthProfile profile = router.fetchProfile("kakao", "code", "state", null);

    Assertions.assertThat(profile.provider()).isEqualTo("kakao");
    Assertions.assertThat(profile.providerUserId()).isEqualTo("kid-1");
  }

  @Test
  void routesToNaverStrategy() {
    OAuthClientRouter router = new OAuthClientRouter(
        List.of(stub("kakao", "kid-1"), stub("naver", "nid-1")));

    OAuthProfile profile = router.fetchProfile("naver", "code", "state", null);

    Assertions.assertThat(profile.provider()).isEqualTo("naver");
    Assertions.assertThat(profile.providerUserId()).isEqualTo("nid-1");
  }

  @Test
  void unsupportedProviderThrows() {
    OAuthClientRouter router = new OAuthClientRouter(
        List.of(stub("kakao", "kid-1"), stub("naver", "nid-1")));

    Assertions.assertThatThrownBy(() -> router.fetchProfile("google", "code", "state", null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getErrorCode())
        .isEqualTo(ErrorCode.UNSUPPORTED_PROVIDER);
  }

  private OAuthProviderStrategy stub(String provider, String providerUserId) {
    return new OAuthProviderStrategy() {
      @Override
      public String provider() {
        return provider;
      }

      @Override
      public OAuthProfile fetchProfile(String code, String state, String redirectUri) {
        return new OAuthProfile(provider, providerUserId);
      }
    };
  }
}
