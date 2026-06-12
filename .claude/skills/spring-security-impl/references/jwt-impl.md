# JWT 구현 상세 가이드

이 프로젝트의 JWT 구현 현황과 미구현 패턴을 정의한다.
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
- `Authorization: Bearer {token}` 헤더에서 토큰 추출
- 유효한 토큰 → `CustomUserDetails(userId, role)` 생성 → `SecurityContext` 저장
- `BusinessException` 발생 시 필터 내에서 JSON 에러 응답 직접 반환 (체인 중단)
- 토큰 없는 요청은 통과 (이후 `@PreAuthorize`에서 차단)

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

## 2. 미구현 — 이 패턴으로 구현

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

    public String get(UUID userId) {
        return redisTemplate.opsForValue().get(PREFIX + userId);
    }

    // RTR: 기존 토큰 원자적 삭제 + 신규 저장. Redis에 없으면 REFRESH_TOKEN_NOT_FOUND(401)
    public void rotate(UUID userId, String newRefreshToken, long ttlMillis) {
        String existing = redisTemplate.opsForValue().getAndDelete(PREFIX + userId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }
        save(userId, newRefreshToken, ttlMillis);
    }

    public void delete(UUID userId) {
        redisTemplate.delete(PREFIX + userId);
    }
}
```

### AuthService 플로우 (`domain/auth/service/AuthService.java`)

**로그인 성공 시**
```
1. userId, role 조회
2. generateAccessToken(userId, role)
3. generateRefreshToken(userId)
4. refreshTokenRepository.save(userId, refreshToken, refreshTokenExpiry)
5. 응답: { access_token, refresh_token }
```

**토큰 재발급 (`POST /api/v1/auth/refresh`)**
```
1. 요청 바디에서 refresh_token 추출
2. jwtProvider.validate(refreshToken) — 만료·위조 시 401
3. jwtProvider.getUserId(refreshToken) → userId
4. refreshTokenRepository.rotate(userId, newRefreshToken, ttl) — Redis에 없으면 401
5. generateAccessToken(userId, role) — role은 DB 재조회
6. 응답: { access_token, refresh_token }
```

**로그아웃 (`POST /api/v1/auth/logout`)**
```
1. SecurityContext에서 userId 추출
2. refreshTokenRepository.delete(userId)
3. 200 OK
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
| Redis에 RT 없음 | `REFRESH_TOKEN_NOT_FOUND` | 401 |
| 권한 없음 | `FORBIDDEN` | 403 |
