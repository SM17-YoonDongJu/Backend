# security-developer 구현 요약

## 구현된 파일

### 최소 JWT 인프라 (global/security)
- `src/main/java/com/soma/backend/global/security/JwtProvider.java`
- `src/main/java/com/soma/backend/global/security/CustomUserDetails.java`
- `src/main/java/com/soma/backend/global/security/JwtFilter.java`
- `src/main/java/com/soma/backend/global/security/SecurityConfig.java`

### WebSocket 인증 (domain/chat/config)
- `src/main/java/com/soma/backend/domain/chat/config/JwtHandshakeInterceptor.java`
- `src/main/java/com/soma/backend/domain/chat/config/StompAuthChannelInterceptor.java`

### 수정된 파일
- `src/main/java/com/soma/backend/global/exception/ErrorCode.java` — `CHAT_WS_UNAUTHORIZED` 추가

## JwtProvider 인터페이스
```java
public String generateAccessToken(UUID userId, String role)
public String generateRefreshToken(UUID userId)
public boolean validateToken(String token)
public UUID getUserId(String token)
public String getRole(String token)
```

## CustomUserDetails 필드
- `userId: UUID`
- `role: String`
- `getAuthorities()` → `ROLE_{role}`
- `getUsername()` → `userId.toString()`

## backend-developer 연동
- REST: `@AuthenticationPrincipal CustomUserDetails principal` → `principal.getUserId()`
- JwtFilter가 Bearer 토큰 검증 후 SecurityContext에 CustomUserDetails 주입

## realtime-developer 연동
- `JwtHandshakeInterceptor.ATTR_USER_ID`, `ATTR_ROLE` 상수로 session attribute 접근
- `ChatWebSocketConfig`에서: `registry.addEndpoint("/ws").addInterceptors(jwtHandshakeInterceptor)`
- `configureClientInboundChannel`에서: `reg.interceptors(stompAuthChannelInterceptor)`
- STOMP 컨트롤러 sender 추출: `Principal` → `Authentication` 캐스팅 → `CustomUserDetails.getUserId()`

## SecurityConfig 경로 정책
- `/ws/**` → permitAll
- `/api/chat/**` → authenticated
- CSRF disable, STATELESS, JwtFilter before UsernamePasswordAuthenticationFilter

## 미구현
- OAuth2 소셜 로그인 (범위 외)
- Redis Refresh Token / /api/auth/reissue (채팅 범위 외)
