# OAuth2 Provider 구현 가이드 (카카오·네이버)

OAuth2 소셜 로그인은 **미구현** 상태. 이 패턴으로 구현한다.
기존 패키지 구조 `domain/auth/` 아래에 구현한다.

---

## 1. application-oauth.yml 설정

`src/main/resources/application-oauth.yml` (이미 존재, 값만 채울 것)

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          kakao:
            client-id: ${KAKAO_CLIENT_ID}
            client-secret: ${KAKAO_CLIENT_SECRET}
            redirect-uri: "{baseUrl}/api/v1/auth/oauth2/callback/{registrationId}"
            authorization-grant-type: authorization_code
            client-authentication-method: client_secret_post
            scope:
              - profile_nickname
              - account_email
          naver:
            client-id: ${NAVER_CLIENT_ID}
            client-secret: ${NAVER_CLIENT_SECRET}
            redirect-uri: "{baseUrl}/api/v1/auth/oauth2/callback/{registrationId}"
            authorization-grant-type: authorization_code
            scope:
              - name
              - email
        provider:
          kakao:
            authorization-uri: https://kauth.kakao.com/oauth/authorize
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            user-name-attribute: id
          naver:
            authorization-uri: https://nid.naver.com/oauth2.0/authorize
            token-uri: https://nid.naver.com/oauth2.0/token
            user-info-uri: https://openapi.naver.com/v1/nid/me
            user-name-attribute: response
```

---

## 2. OAuth2UserInfo 인터페이스 (`domain/auth/oauth2/`)

각 Provider 응답을 정규화하는 인터페이스.

```java
public interface OAuth2UserInfo {
    String getProviderId();     // 소셜 고유 ID
    String getProvider();       // "kakao" | "naver"
    String getNickname();
    String getEmail();          // nullable — 카카오는 선택 동의
}
```

### KakaoOAuth2UserInfo

```java
// 카카오 응답 구조:
// { id: 12345, kakao_account: { profile: { nickname: "..." }, email: "..." } }
public class KakaoOAuth2UserInfo implements OAuth2UserInfo {

    private final Map<String, Object> attributes;

    @Override
    public String getProviderId() {
        return String.valueOf(attributes.get("id"));
    }

    @Override
    public String getProvider() { return "kakao"; }

    @Override
    public String getNickname() {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
        return (String) profile.get("nickname");
    }

    @Override
    public String getEmail() {
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        return (String) kakaoAccount.getOrDefault("email", null);
    }
}
```

### NaverOAuth2UserInfo

```java
// 네이버 응답 구조:
// { resultcode: "00", message: "success", response: { id: "...", name: "...", email: "..." } }
public class NaverOAuth2UserInfo implements OAuth2UserInfo {

    private final Map<String, Object> attributes;

    @Override
    public String getProviderId() {
        Map<String, Object> response = (Map<String, Object>) attributes.get("response");
        return (String) response.get("id");
    }

    @Override
    public String getProvider() { return "naver"; }

    @Override
    public String getNickname() {
        Map<String, Object> response = (Map<String, Object>) attributes.get("response");
        return (String) response.get("name");
    }

    @Override
    public String getEmail() {
        Map<String, Object> response = (Map<String, Object>) attributes.get("response");
        return (String) response.get("email");
    }
}
```

---

## 3. CustomOAuth2UserService (`domain/auth/oauth2/CustomOAuth2UserService.java`)

```java
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        OAuth2UserInfo userInfo = switch (registrationId) {
            case "kakao" -> new KakaoOAuth2UserInfo(oAuth2User.getAttributes());
            case "naver" -> new NaverOAuth2UserInfo(oAuth2User.getAttributes());
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        };

        // SOCIAL_ACCOUNTS 테이블 기준으로 기존 사용자 조회
        User user = socialAccountRepository
            .findByProviderAndProviderUserId(userInfo.getProvider(), userInfo.getProviderId())
            .map(SocialAccount::getUser)
            .orElseGet(() -> createUser(userInfo));

        return new CustomOAuth2User(user, oAuth2User.getAttributes());
    }

    @Transactional
    private User createUser(OAuth2UserInfo userInfo) {
        User user = User.builder()
            .nickname(userInfo.getNickname())
            .email(userInfo.getEmail())
            .role(Role.USER)
            .status(UserStatus.ACTIVE)
            .build();
        userRepository.save(user);

        SocialAccount socialAccount = SocialAccount.builder()
            .user(user)
            .provider(userInfo.getProvider())
            .providerUserId(userInfo.getProviderId())
            .build();
        socialAccountRepository.save(socialAccount);

        return user;
    }
}
```

---

## 4. OAuth2AuthenticationSuccessHandler (`domain/auth/oauth2/`)

로그인 성공 후 JWT 발급 → 프론트엔드로 리다이렉트.

```java
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        UUID userId = oAuth2User.getUserId();
        String role = oAuth2User.getRole();

        String accessToken = jwtProvider.generateAccessToken(userId, role);
        String refreshToken = jwtProvider.generateRefreshToken(userId);
        refreshTokenRepository.save(userId, refreshToken, /* ttl */ 1209600000L);

        // 프론트엔드 리다이렉트 — 토큰을 쿼리 파라미터로 전달
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
            .queryParam("access_token", accessToken)
            .queryParam("refresh_token", refreshToken)
            .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
```

---

## 5. SecurityConfig OAuth2 설정 추가

기존 `SecurityConfig.java`에 OAuth2 설정 추가:

```java
.oauth2Login(oauth2 -> oauth2
    .userInfoEndpoint(info ->
        info.userService(customOAuth2UserService))
    .successHandler(oAuth2AuthenticationSuccessHandler))
```

---

## 6. 추가 필요 환경변수 (`.env.example`)

```
KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
NAVER_CLIENT_ID=
NAVER_CLIENT_SECRET=
OAUTH2_REDIRECT_URI=http://localhost:3000/oauth2/callback
```

---

## 7. 구현 체크리스트

- [ ] `application-oauth.yml` 카카오·네이버 provider 설정
- [ ] `OAuth2UserInfo` 인터페이스
- [ ] `KakaoOAuth2UserInfo`, `NaverOAuth2UserInfo`
- [ ] `CustomOAuth2User` (OAuth2User + userId/role 보유)
- [ ] `CustomOAuth2UserService` — SOCIAL_ACCOUNTS 기반 사용자 조회·생성
- [ ] `OAuth2AuthenticationSuccessHandler` — JWT 발급 후 리다이렉트
- [ ] `SecurityConfig`에 `.oauth2Login()` 추가
- [ ] `SocialAccountRepository` (`provider` + `provider_user_id` 복합 조회)
