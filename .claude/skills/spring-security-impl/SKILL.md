---
name: spring-security-impl
description: "Spring Security 6 기반 JWT(RTR)·HttpOnly 쿠키 인증, OAuth2 소셜 로그인(카카오·네이버, 수동 REST 코드교환), RBAC(USER/CERTIFICATED_ADJUSTER/UNCERTIFICATED_ADJUSTER/ADMIN) 구현 가이드. 인증·인가·보안 설정 작업 시 반드시 이 스킬을 참조."
---

# Spring Security Implementation Guide

이 프로젝트의 Spring Security 구현 패턴을 정의한다.

## 기술 스택
- Spring Boot 4.x + Spring Security 6.x (Spring Framework 7 기반)
- JWT: `jjwt 0.12.x` (`io.jsonwebtoken`), HMAC-SHA256
- OAuth2: **수동 REST 코드교환** — `ClientRegistrationRepository`로 제공자 설정만 재사용하고 Spring `oauth2Login` 필터는 쓰지 않는다
- Redis (Refresh Token 저장)
- RBAC: USER · CERTIFICATED_ADJUSTER · UNCERTIFICATED_ADJUSTER · ADMIN
- **인증 전송: HttpOnly 쿠키** — access/refresh 토큰을 응답 바디가 아니라 `access_token`·`refresh_token` 쿠키로 주고받는다 (`CookieProvider`)

## JWT 구현 패턴

### 토큰 전략
- Access Token: 짧은 만료(기본 30분), `access_token` HttpOnly 쿠키로 전달 — 응답 바디 노출 금지
- Refresh Token: 긴 만료(기본 14일), `refresh_token` HttpOnly 쿠키 + Redis에 `refresh:{userId}` 키로 저장
- RTR(Refresh Token Rotation): 재발급 시 `rotate(userId, oldToken, newToken)`가 Lua로 원자적 CAS(저장값 == oldToken일 때만 교체) — 동시 재발급 경쟁 창 제거, 불일치 시 키 삭제로 재사용·탈취 탐지

### JwtProvider 핵심 메서드 (`global/security/JwtProvider.java`)
```java
String generateAccessToken(UUID userId, String role)  // sub=userId, role 클레임 포함
String generateRefreshToken(UUID userId)              // sub=userId만 (role 없음)
void   validate(String token)                          // 만료→EXPIRED_TOKEN, 위조·형식→INVALID_TOKEN
UUID   getUserId(String token)                         // subject → UUID
String getRole(String token)                           // "role" 클레임
```

### JwtFilter (`global/security/JwtFilter.java`)
- `OncePerRequestFilter` 확장, `@Component`
- 토큰 조회: **`Authorization: Bearer` 헤더 우선**, 없으면 `access_token` 쿠키로 폴백(`resolveToken`)
- `shouldNotFilter`: `/auth/**`는 access 검증을 건너뛴다 — 재발급·로그아웃에 만료된 access 쿠키가 딸려와도 막히면 안 되기 때문(해당 경로는 refresh 쿠키로 동작)
- 유효 토큰 → `CustomUserDetails(userId, role)` 생성 → `SecurityContextHolder` 저장
- `BusinessException` 발생 시 필터 내에서 `ErrorResponse` JSON 직접 응답 후 체인 중단
- 토큰 없는 요청은 통과(익명) → 이후 인가 단계에서 `RestAuthenticationEntryPoint`(401)

## OAuth2 소셜 로그인

Spring `oauth2Login` 필터를 쓰지 않는다. 프론트가 인가코드를 받아 백엔드로 넘기면 서버가 **수동 REST로 토큰·프로필을 교환**한다. `application-oauth.yml`의 `ClientRegistration`(client-id/secret/redirect-uri/token-uri/user-info-uri)만 재사용한다. 외부 호출은 3s/5s(connect/read) 타임아웃을 건다.

### 콜백 플로우 (`GET /auth/oauth2/{provider}/callback?code=&state=`)
```
1. RestClientOAuthClient.fetchProfile(provider, code, state)  // 트랜잭션 밖(외부 HTTP 지연 시 DB 커넥션 점유 방지)
   - ClientRegistration에서 token-uri/user-info-uri 조회
   - code → access_token 교환(POST form) → user-info 조회(Bearer)
   - scope는 id만 — OAuthProfile(provider, providerUserId)로 정규화 (이메일·프로필 미수집)
2. SocialAccount 조회 (provider + providerUserId)
   - 기존 회원 → AuthTokenService.issueTokens(쿠키 발급) → OAuthCallbackResponse.existingUser(userId) (isNewUser=false)
   - 신규 회원 → SignupTicket 발급(쿠키 없음)          → OAuthCallbackResponse.newUser(ticket)   (isNewUser=true)
```

### 회원가입 (`POST /auth/register`, 201)
신규 회원은 콜백에서 받은 **가입 티켓**으로 프로필을 채워 가입한다. **이메일 미수집**, 이름(nickname)·생년월일·전화번호·userType 수집.
```
1. SignupTicketProvider.parse(socialToken)   // 서명·만료(5분)·purpose="signup" 검증
2. provider 일치 + SocialAccount/전화번호 중복 검사
3. User + SocialAccount 한 트랜잭션 생성
4. AuthTokenService.issueTokens(쿠키 발급) → RegisterResponse
```

> **SignupTicket**(`SignupTicketProvider`): `jwt.secret`으로 서명한 단기(5분) JWT. `purpose="signup"` 클레임으로 액세스 토큰과 구분하고, subject=providerUserId·provider 클레임을 담는다. 토큰을 쿼리 파라미터로 프론트에 리다이렉트하지 않는다(쿠키 인증이라 리다이렉트-토큰 패턴 불필요).

> Provider별 응답 파싱·엔드포인트 상세: `references/oauth2-providers.md`

## RBAC 구현

### 권한 계층
```
ADMIN > CERTIFICATED_ADJUSTER > USER
UNCERTIFICATED_ADJUSTER: 로그인 가능, 케이스 채택 등 핵심 API 접근 시 403
```

### 엔드포인트 접근 제어 (패턴 예시)
```java
// SecurityConfig — 실제 permitAll 목록은 아래 "SecurityConfig 기본 구조" 참조
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/adjuster/**").hasAnyRole("CERTIFICATED_ADJUSTER", "ADMIN")
.anyRequest().authenticated()
```

### 메서드 레벨 권한
```java
@PreAuthorize("hasRole('ADMIN')")
@PreAuthorize("hasRole('CERTIFICATED_ADJUSTER') and #adjusterId == authentication.principal.userId")
```

## SecurityConfig 기본 구조 (`global/security/SecurityConfig.java`)
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)   // 쿠키 인증이지만 SameSite + CORS 오리진 화이트리스트로 CSRF 완화
            .anonymous(AbstractHttpConfigurer::disable)  // 미인증은 인증 null → @PreAuthorize 진입 시 401(익명 토큰이면 403으로 새 401/403 구분 붕괴)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 실제: /ws-chat/**(WebSocket 핸드셰이크)도 permitAll, dev에선 app.docs.public=true 시 /docs·/scalar.html·/v3/api-docs 개방
                .requestMatchers("/auth/**", "/ws/**", "/ws-chat/**", "/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(restAuthenticationEntryPoint)  // 401 LOGIN_REQUIRED
                .accessDeniedHandler(restAccessDeniedHandler))           // 403 FORBIDDEN
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

인증 실패(401)는 `RestAuthenticationEntryPoint`, 인가 거부(403)는 `RestAccessDeniedHandler`가 각각 `ErrorResponse` JSON으로 응답한다.

## CORS 설정 (쿠키 인증)

쿠키 인증이라 `allowCredentials(true)`가 필수이고, 자격증명과 함께 쓸 수 없는 와일드카드 `*` 대신 **`allowedOriginPatterns`**로 구성한다. 오리진은 `app.cors.allowed-origin-patterns`(쉼표 구분 패턴 목록, 기본 `http://localhost:3000`)로 주입한다.

```java
CorsConfiguration config = new CorsConfiguration();
config.setAllowedOriginPatterns(allowedOriginPatterns);  // 예: https://앱도메인, https://*.vercel.app
config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
config.setAllowCredentials(true);
```

**쿠키 동작 프로퍼티**: `app.cookie.secure`(기본 `true`, 로컬 http는 `false`), `app.cookie.same-site`(기본 `Lax`, cross-site 운영은 `None`+https). 쿠키는 모두 HttpOnly로 발급하되 Path는 나눈다 — `access_token`은 모든 보호 API에서 검증돼야 하므로 Path `/`, `refresh_token`은 재발급·로그아웃(`/auth/**`)에만 전송되도록 Path `/auth`로 좁혀 장기 크리덴셜의 노출 표면을 줄인다. 만료 쿠키(Max-Age=0)도 발급 시 Path와 일치해야 브라우저가 매칭해 삭제한다.

## Redis Refresh Token 관리 (security-developer 담당)

Redis는 JWT Refresh Token 저장 전용으로 사용한다.

```java
@Component
public class RefreshTokenRepository {
    private static final String PREFIX = "refresh:";

    // 최초·소셜 로그인: 덮어쓰기 저장
    public void save(UUID userId, String refreshToken, long ttlMillis) {
        redisTemplate.opsForValue()
            .set(PREFIX + userId, refreshToken, ttlMillis, TimeUnit.MILLISECONDS);
    }

    // RTR: Lua 원자적 CAS — 저장값 == oldToken일 때만 newToken으로 교체.
    // 삭제-저장 공백이 없어 경쟁 창(race window)·토큰 유실이 없다.
    // 반환: ROTATED(성공) / NOT_FOUND(만료·미존재) / MISMATCH(이미 회전됨·탈취 의심 → 키 삭제)
    private static final String ROTATE_LUA =
        "local current = redis.call('GET', KEYS[1]) "
        + "if current == false then return -1 end "
        + "if current == ARGV[1] then "
        + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 end "
        + "redis.call('DEL', KEYS[1]) return 0";
    private static final RedisScript<Long> ROTATE_SCRIPT = RedisScript.of(ROTATE_LUA, Long.class);

    public RotateResult rotate(UUID userId, String oldToken, String newToken) {
        Long result = redisTemplate.execute(
            ROTATE_SCRIPT, List.of(PREFIX + userId), oldToken, newToken, String.valueOf(ttlMillis));
        if (result != null && result == 1L) return RotateResult.ROTATED;
        if (result != null && result == 0L) return RotateResult.MISMATCH;
        return RotateResult.NOT_FOUND;
    }

    // 로그아웃
    public void delete(UUID userId) {
        redisTemplate.delete(PREFIX + userId);
    }

    public enum RotateResult { ROTATED, NOT_FOUND, MISMATCH }
}
```

> 발급·회전·쿠키 부착 오케스트레이션은 `AuthTokenService`(`issueTokens`/`validateRefreshCookie`+`reissueTokens`/`clearTokens`)가 담당한다.

## 보안 체크리스트
- [ ] 토큰은 HttpOnly 쿠키로만 전달, 응답 바디·쿼리 파라미터에 노출 금지
- [ ] Refresh Token은 Redis에만 저장, DB에 평문 저장 금지
- [ ] OAuth2 Client Secret은 환경변수로 관리
- [ ] 토큰 만료 시 401, 권한 부족 시 403 응답
- [ ] RT 저장값 없음(NOT_FOUND)·불일치(MISMATCH, 재사용·탈취 의심) 시 401 (MISMATCH는 키 삭제)
- [ ] CORS는 `allowCredentials(true)` + `allowedOriginPatterns`(와일드카드 금지)

## 상세 구현
- JWT 전체 구현: `references/jwt-impl.md`
- OAuth2 Provider별 응답 파싱: `references/oauth2-providers.md`
