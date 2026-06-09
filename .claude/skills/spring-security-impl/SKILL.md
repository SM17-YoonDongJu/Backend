---
name: spring-security-impl
description: "Spring Security 6 기반 JWT(RTR), OAuth2 소셜 로그인(카카오·네이버), RBAC(USER/ADJUSTER/ADMIN) 구현 가이드. 인증·인가·보안 설정 작업 시 반드시 이 스킬을 참조."
---

# Spring Security Implementation Guide

이 프로젝트의 Spring Security 구현 패턴을 정의한다.

## 기술 스택
- Spring Boot 3.x + Spring Security 6.x
- JWT (jjwt 또는 spring-security-oauth2-resource-server)
- OAuth2 Client (카카오·네이버 커스텀 Provider)
- Redis (Refresh Token 저장)
- RBAC: USER · CERTIFICATED_ADJUSTER · UNCERTIFICATED_ADJUSTER · ADMIN

## JWT 구현 패턴

### 토큰 전략
- Access Token: 짧은 만료 (15분~1시간), HTTP 응답 바디 또는 쿠키
- Refresh Token: 긴 만료 (30일), Redis에 `refresh:{userId}` 키로 저장
- RTR(Refresh Token Rotation): 재발급 시 기존 RT 삭제 + 새 RT 발급

### JwtProvider 핵심 메서드
```java
String generateAccessToken(Authentication authentication)
String generateRefreshToken(Long userId)
boolean validateToken(String token)
Authentication getAuthentication(String token)
```

### JwtAuthenticationFilter
- `OncePerRequestFilter` 확장
- `Authorization: Bearer {token}` 헤더 추출
- 유효한 토큰이면 `SecurityContextHolder`에 Authentication 설정

## OAuth2 소셜 로그인

### 카카오·네이버 Provider 설정
- `spring.security.oauth2.client.registration.kakao` 커스텀 Provider 설정
- `CustomOAuth2UserService`에서 각 Provider 응답 정규화 → `OAuth2UserInfo` 인터페이스
- `OAuth2AuthenticationSuccessHandler`: JWT 발급 후 프론트엔드 리다이렉트

### 신규 사용자 처리 플로우
```
OAuth2 로그인 성공 → CustomOAuth2UserService.loadUser()
  → Provider별 응답 파싱 (KakaoOAuth2UserInfo, NaverOAuth2UserInfo)
  → DB에 사용자 존재 확인
  → 없으면 신규 회원 생성 (ROLE_USER 부여)
  → JWT 발급 (AccessToken + RefreshToken)
  → 리다이렉트
```

## RBAC 구현

### 권한 계층
```
ADMIN > CERTIFICATED_ADJUSTER > USER
UNCERTIFICATED_ADJUSTER: 로그인 가능, 케이스 채택 등 핵심 API 접근 시 403
```

### 엔드포인트 접근 제어 예시
```java
// SecurityConfig
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/adjuster/**").hasAnyRole("CERTIFICATED_ADJUSTER", "ADMIN")
.requestMatchers("/api/user/**").authenticated()
.requestMatchers("/api/public/**").permitAll()
```

### 메서드 레벨 권한
```java
@PreAuthorize("hasRole('ADMIN')")
@PreAuthorize("hasRole('CERTIFICATED_ADJUSTER') and #adjusterId == authentication.principal.id")
```

## SecurityConfig 기본 구조
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(info -> info.userService(customOAuth2UserService))
                .successHandler(oAuth2AuthenticationSuccessHandler))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/public/**").permitAll()
                .anyRequest().authenticated())
            .build();
    }
}
```

## CORS 설정 (프로파일 분리)
```yaml
# application-dev.yml
cors:
  allowed-origins: http://localhost:3000
# application-prod.yml
cors:
  allowed-origins: https://your-domain.com
```

## Redis Refresh Token 관리 (security-developer 담당)

Redis는 JWT Refresh Token 저장 전용으로만 사용한다.

```java
@Component
public class RefreshTokenRepository {
    private static final String PREFIX = "refresh:";

    // 저장
    public void save(UUID userId, String refreshToken, long ttlMillis) {
        redisTemplate.opsForValue()
            .set(PREFIX + userId, refreshToken, ttlMillis, TimeUnit.MILLISECONDS);
    }

    // RTR: 재발급 시 기존 삭제 + 신규 저장
    public String reissue(UUID userId, String newRefreshToken, long ttlMillis) {
        String existing = redisTemplate.opsForValue().getAndDelete(PREFIX + userId);
        if (existing == null) throw new InvalidTokenException("RT not found or expired");
        save(userId, newRefreshToken, ttlMillis);
        return newRefreshToken;
    }

    // 로그아웃
    public void delete(UUID userId) {
        redisTemplate.delete(PREFIX + userId);
    }
}
```

## 보안 체크리스트
- [ ] Refresh Token은 Redis에만 저장, DB에 평문 저장 금지
- [ ] OAuth2 Client Secret은 환경변수로 관리
- [ ] 토큰 만료 시 401, 권한 부족 시 403 응답
- [ ] Redis에 없는 RT 재사용 시 401 (탈취 감지)

## 상세 구현
- JWT 전체 구현: `references/jwt-impl.md`
- OAuth2 Provider별 응답 파싱: `references/oauth2-providers.md`
