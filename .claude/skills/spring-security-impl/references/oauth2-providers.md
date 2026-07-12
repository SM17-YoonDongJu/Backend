# OAuth2 Provider 구현 가이드 (카카오·네이버)

OAuth2 소셜 로그인은 **구현 완료** 상태. Spring `oauth2Login` 필터를 쓰지 않고, 프론트가 받은 인가코드를 백엔드가 수동 REST로 교환한다. **기존 코드와 다른 방식으로 구현하지 않는다.** 패키지: `domain/auth/`.

핵심 원칙:
- **인증 전송은 HttpOnly 쿠키.** 토큰을 쿼리 파라미터로 프론트에 리다이렉트하지 않는다.
- **scope는 `id`만.** 프로필·이메일 동의를 받지 않으므로 providerUserId만 정규화한다(이름·생년월일·전화번호는 회원가입 단계에서 수집).

---

## 1. application-oauth.yml 설정

`ClientRegistrationRepository`가 이 값을 읽어 `RestClientOAuthClient`가 수동 교환에 사용한다. `redirect-uri`의 `{baseUrl}`은 `app.oauth.base-url`(기본 `http://localhost:8080`)로 치환되며, 프론트가 인가 요청에 쓴 redirect_uri와 정확히 일치해야 제공자가 토큰을 발급한다.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          kakao:
            client-id: ${KAKAO_CLIENT_ID}
            client-secret: ${KAKAO_CLIENT_SECRET}
            redirect-uri: "{baseUrl}/auth/oauth2/kakao/callback"
            authorization-grant-type: authorization_code
            client-authentication-method: client_secret_post
            scope:
              - account_email        # 최소 scope. 프로필 동의는 받지 않는다(id만 사용)
          naver:
            client-id: ${NAVER_CLIENT_ID}
            client-secret: ${NAVER_CLIENT_SECRET}
            redirect-uri: "{baseUrl}/auth/oauth2/naver/callback"
            authorization-grant-type: authorization_code
        provider:
          kakao:
            token-uri: https://kauth.kakao.com/oauth/token
            user-info-uri: https://kapi.kakao.com/v2/user/me
            user-name-attribute: id
          naver:
            token-uri: https://nid.naver.com/oauth2.0/token
            user-info-uri: https://openapi.naver.com/v1/nid/me
            user-name-attribute: response
```

> `authorization-uri`는 프론트가 인가 요청에 쓰므로 백엔드 교환에는 불필요하다. 백엔드는 `token-uri`·`user-info-uri`·client 자격증명·redirect-uri만 사용한다.

---

## 2. OAuthClient — 수동 코드교환 (`domain/auth/service/provider/`)

`OAuthClient` 인터페이스와 `RestClientOAuthClient` 구현이 code → access_token → user-info를 수동으로 교환한다.

```java
public interface OAuthClient {
    OAuthProfile fetchProfile(String provider, String code, String state);
}

// 정규화된 프로필 — id만 담는다(이메일·닉네임 미수집)
public record OAuthProfile(String provider, String providerUserId) {}
```

`RestClientOAuthClient` 요지:
- `ClientRegistrationRepository.findByRegistrationId(provider)` → 없으면 `UNSUPPORTED_PROVIDER`
- **connect 3s / read 5s** 타임아웃(`SimpleClientHttpRequestFactory`)
- `POST token-uri`(form: grant_type·client_id·client_secret·code·redirect_uri) → `access_token` 없으면 `EXTERNAL_API_ERROR`
- `GET user-info-uri`(Authorization: Bearer) → Map 파싱
- 예외 매핑: 토큰 교환 4xx → `INVALID_REQUEST`(잘못된 인가코드 등), 그 외 HTTP 오류·타임아웃(`ResourceAccessException`) → `EXTERNAL_API_ERROR`

```java
// 카카오: { id: 12345, ... }              → providerUserId = id
// 네이버: { response: { id: "...", ... } } → providerUserId = response.id
private OAuthProfile parseKakao(Map<String, Object> body) { /* body.get("id") */ }
private OAuthProfile parseNaver(Map<String, Object> body) { /* body.get("response").get("id") */ }
```

> 응답에서 `id`가 없으면 `EXTERNAL_API_ERROR`. 트랜잭션 밖에서 호출한다(외부 HTTP 지연이 DB 커넥션을 점유하지 않도록).

---

## 3. 콜백 유스케이스 (`OAuthLoginService`)

```
handleCallback(response, provider, code, state):
  1. profile = oAuthClient.fetchProfile(provider, code, state)   // 트랜잭션 밖
  2. socialAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
       존재 → user 조회(없으면 USER_NOT_FOUND) → issueTokens(쿠키) → existingUser(userId)
       없음 → signupTicketProvider.issue(provider, providerUserId) → newUser(ticket)
```

`OAuthCallbackResponse(userId, isNewUser, signupTicket)`:
- 기존 회원: `existingUser(userId)` → `isNewUser=false`, 쿠키 발급됨
- 신규 회원: `newUser(ticket)` → `isNewUser=true`, 쿠키 없음, `signupTicket` 반환

---

## 4. 회원가입 (`AuthRegisterService`, `POST /auth/register`)

가입 티켓(단기 5분 JWT, `purpose="signup"`)으로 프로필을 채워 가입한다. **이메일 미수집.**

```
register(response, RegisterRequest):
  1. ticket = signupTicketProvider.parse(socialToken)       // 서명·만료·purpose 검증 실패 시 INVALID_TOKEN
  2. ticket.provider == request.provider 아니면 INVALID_TOKEN
  3. SocialAccount(provider+providerUserId)·phoneNumber 중복 → DUPLICATE_RESOURCE
  4. Role = UserType.from(userType).toRole()
  5. User.create(nickname, birthDate, phoneNumber, role) + SocialAccount.create(...) 한 트랜잭션 저장
  6. issueTokens(쿠키) → RegisterResponse(userId, nickname, role)
```

`RegisterRequest`(snake_case, 검증): `provider`, `social_token`, `nickname`(1~30자, 실명), `birth_date`(과거), `phone_number`(`^01[0-9]-?\d{3,4}-?\d{4}$`, unique), `user_type`(`insured_person|adjuster`).

---

## 5. SecurityConfig — oauth2Login 없음

Spring `oauth2Login`을 **추가하지 않는다.** 모든 인증 진입점은 `/auth/**`(permitAll)로 노출된 REST 컨트롤러이고, 쿠키는 `AuthTokenService`가 부착한다. SecurityConfig 상세는 `SKILL.md`의 "SecurityConfig 기본 구조" 참조.

---

## 6. 환경변수 (`.env.example`)

```
KAKAO_CLIENT_ID=
KAKAO_CLIENT_SECRET=
NAVER_CLIENT_ID=
NAVER_CLIENT_SECRET=
# app.oauth.base-url — redirect-uri {baseUrl} 치환용 (기본 http://localhost:8080)
```

---

## 7. 에러 코드

| 상황 | ErrorCode | HTTP |
|------|-----------|------|
| 미지원 provider | `UNSUPPORTED_PROVIDER` | 400 |
| 잘못된 인가코드(토큰 교환 4xx) | `INVALID_REQUEST` | 400 |
| 제공자 응답 오류·타임아웃·id 누락 | `EXTERNAL_API_ERROR` | 502 |
| 가입 티켓 위조·만료·purpose 불일치·provider 불일치 | `INVALID_TOKEN` | 401 |
| 소셜계정·전화번호 중복 | `DUPLICATE_RESOURCE` | 409 |
| 소셜계정은 있으나 user 없음 | `USER_NOT_FOUND` | 404 |

> HTTP 상태는 각 `ErrorCode` enum 정의를 기준으로 확인할 것.

---

## 8. 구현 체크리스트

- [x] `application-oauth.yml` 카카오·네이버 registration/provider 설정 (scope는 최소)
- [x] `OAuthClient` + `RestClientOAuthClient` — 수동 코드교환, 타임아웃, 예외 매핑
- [x] `OAuthProfile` — providerUserId만 정규화
- [x] `OAuthLoginService` — SocialAccount 기반 로그인/가입 분기
- [x] `SignupTicketProvider` — 단기 가입 티켓 발급·검증
- [x] `AuthRegisterService` — 티켓 검증 + User/SocialAccount 생성 + 쿠키 발급
- [x] `AuthController` — `/auth/oauth2/{provider}/callback`, `/auth/register`, `/auth/logout`, `/auth/reissue`
- [x] `SocialAccountRepository` — `provider` + `provider_user_id` 복합 조회
