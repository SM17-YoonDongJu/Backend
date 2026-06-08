# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# 로컬 인프라 실행 (PostgreSQL, Redis)
docker compose up -d

# 앱 빌드
./gradlew build

# 테스트 실행
./gradlew test

# 단일 테스트 실행
./gradlew test --tests "com.soma.backend.domain.auth.*"

# 앱 실행 (로컬)
./gradlew bootRun

# Checkstyle 검사
./gradlew checkstyleMain

# JAR 빌드 (테스트 제외)
./gradlew bootJar -x test

# 전체 배포 (앱 포함)
docker compose --profile app up -d
```

## Architecture

Spring Boot 3.4.x/ Java 21 기반 REST API 서버. 레이어드 아키텍처를 따른다.

```
com.soma.backend
├── domain/      # 비즈니스 도메인별 Controller·Service·Repository
├── global/      # 전 도메인 공통 (config, exception, response, security)
└── infra/       # 외부 시스템 연동 (redis, s3, fcm)
```

**domain** 패키지 내부는 도메인별로 `controller`, `service`, `dto` 서브패키지로 구성한다. 도메인: `auth`, `user`, `adjuster`, `report`, `matching`, `payment`, `chat`.

**global/security** — `JwtProvider`로 토큰 생성·검증, `JwtFilter`(OncePerRequestFilter)로 요청마다 인증 처리, `CustomUserDetails`에 `userId`와 `role`을 담아 `SecurityContext`에 저장한다.

**global/exception** — 모든 예외는 `BusinessException(ErrorCode)`으로 던지고 `GlobalExceptionHandler`가 `ApiResponse<Void>` 형태로 응답한다.

**global/response** — 모든 API 응답은 `ApiResponse<T>`로 통일한다 (`success`, `message`, `data`).

**infra/redis** — `RefreshTokenRepository`가 `RedisTemplate<String, String>`으로 Refresh Token을 `refresh:{userId}` 키로 관리한다 (TTL 14일).

**infra/s3** — `S3Client` Bean은 `global/config/S3Config`에서 `aws.*` 프로퍼티로 직접 구성한다 (Spring Cloud AWS 미사용).

## Key Configuration

환경변수는 `.env.example` 참고. 필수값: `DB_PASSWORD`, `JWT_SECRET`, `AWS_*`, `KAKAO_*`, `NAVER_*`.

로컬 개발 시 DB/Redis 기본값이 적용되므로 `docker compose up -d`만 실행하면 된다.

DB 스키마는 Flyway로 관리한다 (`src/main/resources/db/migration/V{n}__{description}.sql`). JPA `ddl-auto`는 `validate`로 고정.

## Constraints

- 응답 포맷은 항상 `ApiResponse<T>` 사용
- 예외는 `BusinessException` + `ErrorCode` enum 사용, `GlobalExceptionHandler`에서 처리
- JWT secret은 최소 32자 이상
- `open-in-view: false` — 서비스 레이어 안에서 트랜잭션 완료 후 응답

## Spring Boot 담당 범위

FastAPI가 담당하는 영역 (Spring Boot 범위 외):
- 리포트 생성 요청 수신, AI 챗봇 WebSocket
- OCR 처리, LangGraph 멀티에이전트, RAG
- Kafka 내부 처리

Spring Boot가 담당하는 영역:
- 인증·회원 (JWT, OAuth2, RBAC)
- 손해사정사 매칭 플로우 (요청·수락·거절)
- 검수 리포트 등록, review_feedback 수집
- 구독·결제 (PG사 연동)
- FCM Push (검수 완료 시)
