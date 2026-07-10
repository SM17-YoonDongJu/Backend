# JWT 구현 상세 가이드

이 프로젝트의 JWT·쿠키 인증 구현 현황을 정의한다(전량 구현 완료).
**기존 코드와 다른 방식으로 구현하지 않는다.**

---

## 1. 구현 완료 — 변경 금지

### JwtProvider (`global/security/JwtProvider.java`)

- 라이브러리: `jjwt 0.12.x`
- 서명 알고리즘: HMAC-SHA (Keys.hmacShaKeyFor)
- 설정값: `application.yml`의 `jwt.*` 프로퍼티

```java
// Access Token: subject=userId(UUID), role 클레임 포함
public String generateAccessToken(UUID userId, String role)

// Refresh Token: subject=userId(UUID)만 포함, role 없음
public String generateRefreshToken(UUID userId)

// 검증: 만료 → EXPIRED_TOKEN(401), 위조·형식 오류 → INVALID_TOKEN(401)
public void validate(String token)

// 클레임 추출
public UUID getUserId(String token)   // subject → UUID
public String getRole(String token)   // "role" 클레임
```

**클레임 구조**
```
Access Token  : { sub: "{UUID}", role: "USER|CERTIFICATED_ADJUSTER|...", iat, exp }
Refresh Token : { sub: "{UUID}", iat, exp }
```

### JwtFilter (`global/security/JwtFilter.java`)

- `OncePerRequestFilter` 확장, `@Component`
- 토큰 조회: **`access_token` 쿠키 우선**, `Authorization: Bearer {token}` 헤더는 폴백으로만 허용
- `shouldNotFilter`: `/auth/**`는 access 검증을 건너뛴다 (재발급·로그아웃은 refresh 쿠키로 동작 — 만료 access 쿠키가 딸려와도 막히면 안 됨)
- 유효한 토큰 → `CustomUserDetails(userId, role)` 생성 → `SecurityContext` 저장
- `BusinessException` 발생 시 필터 내에서 `ErrorResponse` JSON 직접 반환 (체인 중단)
- 토큰 없는 요청은 통과(익명) → 이후 인가 단계에서 `RestAuthenticationEntryPoint`(401) 또는 `@PreAuthorize`에서 차단

### CustomUserDetails (`global/security/CustomUserDetails.java`)

```java
// SecurityContext에 저장되는 인증 주체
CustomUserDetails {
    UUID userId;
    String role;
    // getAuthorities() → [ROLE_{role}] — "ROLE_" 접두사 자동 추가
}
```

**SecurityContext에서 userId 추출 패턴 (서비스 계층)**
```java
CustomUserDetails userDetails =
    (CustomUserDetails) SecurityContextHolder.getContext()
        .getAuthentication().getPrincipal();
UUID userId = userDetails.getUserId();
```

---

## 2. 구현 완료 — 쿠키 기반 발급·회전

토큰은 응답 바디가 아니라 HttpOnly 쿠키로만 전달된다. 발급·회전·쿠키 부착은 `global/security/AuthTokenService`가 오케스트레이션하고, 도메인 인증 서비스(`OAuthLoginService`·`AuthRegisterService`·`AuthReissueService`·`AuthLogoutService`)가 이를 호출한다.

### RefreshTokenRepository (`infra/redis/RefreshTokenRepository.java`)

Redis는 Refresh Token 저장 전용. `RedisTemplate<String, String>` 빈 사용 (`infra/redis/RedisConfig.java`).

```java
@Component
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String PREFIX = "refresh:";
    private final RedisTemplate<String, String> redisTemplate;

    public void save(UUID userId, String refreshToken, long ttlMillis) {
        redisTemplate.opsForValue()
            .set(PREFIX + userId, refreshToken, ttlMillis, TimeUnit.MILLISECONDS);
    }

    // RTR: Lua 원자적 CAS(compare-and-swap).
    // GET → 저장값 == oldToken이면 SET(PX)로 교체, 아니면 DEL. 단일 원자 연산이라
    // 삭제-저장 공백이 없어 동시 재발급 경쟁 창(race window)·토큰 유실이 없다.
    private static final String ROTATE_LUA =
        "local current = redis.call('GET', KEYS[1]) "
        + "if current == false then return -1 end "        // 저장값 없음 → NOT_FOUND
        + "if current == ARGV[1] then "                     // 일치 → 교체
        + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 end "
        + "redis.call('DEL', KEYS[1]) return 0";            // 불일치 → 키 삭제(탈취/재사용 탐지)
    private static final RedisScript<Long> ROTATE_SCRIPT = RedisScript.of(ROTATE_LUA, Long.class);

    public RotateResult rotate(UUID userId, String oldToken, String newToken) {
        Long result = redisTemplate.execute(
            ROTATE_SCRIPT, List.of(PREFIX + userId), oldToken, newToken, String.valueOf(ttlMillis));
        if (result != null && result == 1L) {
            return RotateResult.ROTATED;
        }
        if (result != null && result == 0L) {
            return RotateResult.MISMATCH;
        }
        return RotateResult.NOT_FOUND;
    }

    public void delete(UUID userId) {
        redisTemplate.delete(PREFIX + userId);
    }

    // 저장값 == oldToken일 때만 교체 성공. MISMATCH는 이미 회전됨·탈취 의심 → 호출부에서 INVALID_TOKEN(401)
    public enum RotateResult { ROTATED, NOT_FOUND, MISMATCH }
}
```

### AuthTokenService 플로우 (`global/security/AuthTokenService.java`)

**토큰 발급 — `issueTokens` (OAuth 로그인·회원가입 성공 시)**
```
1. 호출 도메인 서비스가 userId, role을 넘김 (role 조회는 도메인 서비스 책임)
2. generateAccessToken(userId, role)
3. generateRefreshToken(userId)
4. refreshTokenRepository.save(userId, refreshToken)   // Redis, TTL 재사용
5. access_token·refresh_token HttpOnly 쿠키를 응답에 부착 (바디 노출 없음)
```

**토큰 재발급 (`POST /auth/reissue`)** — Redis를 건드리는 검증-교체는 원자적 회전 한 번으로 처리한다(경쟁 창 제거). 2단계로 분리:

```
[1단계] validateRefreshCookie — 서명·만료만 검증, Redis 미접근
1. refresh_token 쿠키 추출 (없으면 LOGIN_REQUIRED 401)
2. jwtProvider.validate(refreshToken) — 만료·위조 시 401
3. jwtProvider.getUserId(refreshToken) → userId
   → RefreshTokenContext(userId, oldRefreshToken) 반환

[2단계] reissueTokens — 새 토큰 생성 후 Redis 원자적 CAS 회전
4. role은 DB 재조회, newAccess/newRefresh 생성
5. refreshTokenRepository.rotate(userId, oldToken, newRefreshToken):
     - ROTATED   → 새 access/refresh 쿠키 부착
     - NOT_FOUND → REFRESH_TOKEN_NOT_FOUND(401)  (만료·미존재)
     - MISMATCH  → INVALID_TOKEN(401)             (이미 회전됨·탈취 의심, Lua가 키 삭제)
6. 성공 응답은 바디 없이 200 + 갱신된 쿠키
```

> 저장값 대조를 Java(find→비교→save)가 아니라 Lua CAS로 옮긴 이유: 동시 재발급 시 두 요청이 같은 old 토큰으로 통과해 둘 다 새 토큰을 발급받는 경쟁 창을 없애기 위함. `rotate`가 `oldToken`을 넘겨받아 저장값과 원자적으로 대조한다.

**로그아웃 (`POST /auth/logout`)** — `clearTokens`, 인증 없어도 멱등
```
1. principal이 있으면 userId 추출(없으면 null)
2. userId != null → refreshTokenRepository.delete(userId)
3. access_token·refresh_token 만료 쿠키(Max-Age=0) 부착 → 200 OK
```

---

## 3. 설정 (`application.yml`)

```yaml
jwt:
  secret: ${JWT_SECRET}              # 최소 32자 이상 필수
  access-token-expiry: 1800000       # 30분 (ms)
  refresh-token-expiry: 1209600000   # 14일 (ms) — domain-glossary: 30일 목표, 현재 14일
```

> `JWT_SECRET` 미설정 시 로컬 기본값(`localdevonlysecretkeyatleast32chars!!`) 사용.
> 프로덕션에서는 반드시 환경변수로 주입.

---

## 4. 에러 코드 (ErrorCode.java 기준)

| 상황 | ErrorCode | HTTP |
|------|-----------|------|
| 만료된 토큰 | `EXPIRED_TOKEN` | 401 |
| 위조·형식 오류 | `INVALID_TOKEN` | 401 |
| 로그인 필요 | `LOGIN_REQUIRED` | 401 |
| Redis에 RT 없음(rotate NOT_FOUND) | `REFRESH_TOKEN_NOT_FOUND` | 401 |
| RT 저장값 불일치(rotate MISMATCH, 재사용·탈취 의심) | `INVALID_TOKEN` | 401 |
| 권한 없음 | `FORBIDDEN` | 403 |
