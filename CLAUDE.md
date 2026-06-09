# CLAUDE.md

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
├── global/      # 전 도메인 공통 (config, exception, security)
└── infra/       # 외부 시스템 연동 (redis, s3, fcm)
```

**domain** 패키지 내부는 도메인별로 `controller`, `service`, `dto` 서브패키지로 구성한다. 도메인: `auth`, `user`, `adjuster`, `chat`.

**global/security** — `JwtProvider`로 토큰 생성·검증, `JwtFilter`(OncePerRequestFilter)로 요청마다 인증 처리, `CustomUserDetails`에 `userId`와 `role`을 담아 `SecurityContext`에 저장한다.

**global/exception** — 모든 예외는 `BusinessException(ErrorCode)`으로 던지고 `GlobalExceptionHandler`가 `ErrorResponse` (`{ "status": "400", "code": "ERROR_CODE", "message": "..." }`) 형태로 응답한다.

**infra/redis** — `RefreshTokenRepository`가 `RedisTemplate<String, String>`으로 Refresh Token을 `refresh:{userId}` 키로 관리한다 (TTL 14일).

**infra/s3** — `S3Client` Bean은 `infra/s3/S3Config`에서 `aws.*` 프로퍼티로 직접 구성한다 (Spring Cloud AWS 미사용).

## Key Configuration

환경변수는 `.env.example` 참고. 필수값: `DB_PASSWORD`, `JWT_SECRET`, `AWS_*`, `KAKAO_*`, `NAVER_*`.

로컬 개발 시 DB/Redis 기본값이 적용되므로 `docker compose up -d`만 실행하면 된다.

DB 스키마는 Flyway로 관리한다 (`src/main/resources/db/migration/V{n}__{description}.sql`). JPA `ddl-auto`는 `validate`로 고정.

## Git Conventions

- 커밋 메시지는 **항상 한국어**로 작성한다.
- 형식: `<type>(<scope>): <한국어 설명>` (Conventional Commits 준수)
- 예시: `feat(auth): 카카오 OAuth2 소셜 로그인 구현`, `fix(matching): 매칭 수락 시 중복 채팅방 생성 버그 수정`

## Code Conventions

Checkstyle(`config/checkstyle/checkstyle.xml`)가 강제하는 규칙 — 위반 시 빌드 실패.

**포맷**
- 들여쓰기: 스페이스만 사용 (탭 금지)
- 최대 줄 길이: 120자 (package·import·URL 제외)
- 파일 마지막 줄: 빈 줄 필수

**네이밍**
- 클래스: `PascalCase`
- 메서드·파라미터·지역변수·필드: `camelCase`
- 상수(`static final`): `UPPER_SNAKE_CASE`
- 패키지: 소문자, 숫자·언더스코어 금지

**임포트**
- 와일드카드 임포트(`*`) 금지
- 미사용·중복 임포트 금지

**블록**
- 모든 `if`/`for`/`while` 등에 중괄호 필수 (한 줄이라도)
- catch 블록 비워두기 금지 (주석이라도 작성)

**코딩**
- 한 줄에 문장 하나
- 변수 한 번에 하나씩 선언
- 배열 타입: `String[] args` 형식 (`String args[]` 금지)
- long 리터럴: `L` 대문자 사용 (`100l` → `100L`)
- `equals()`/`hashCode()` 둘 다 구현하거나 둘 다 구현 안 하거나
- `switch`에 `default` 필수, `fall-through` 금지

## Constraints

- 응답 포맷: 성공 `{ "status": "200", "message": "...", "data": { ... } }`, 실패 `{ "status": "400", "code": "ERROR_CODE", "message": "..." }`, 필드명은 snake_case (Jackson 전역 설정)
- 성공 응답은 `ApiResponse.ok(data)` / `ApiResponse.ok(message, data)` / `ApiResponse.ok()` 사용, 컨트롤러는 `ResponseEntity<ApiResponse<T>>` 반환
- 엔티티 PK는 UUID 사용, `CustomUserDetails.userId`도 UUID
- 에러는 `BusinessException` + `ErrorCode` enum, `GlobalExceptionHandler`가 `ErrorResponse`로 처리
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
- WebSocket(STOMP) 채팅 (ChatRoom, ChatMessage, 오프라인 FCM 푸시)

## 하네스: Spring Boot Backend

**목표:** 전문 에이전트 팀으로 Spring Boot 피처를 분석·구현·검증한다.

**트리거:** 피처 구현, API 추가, 버그 수정, 채팅/WebSocket 작업 요청 시 `springboot-dev` 스킬을 사용하라. 단순 질문은 직접 응답 가능.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-06-09 | 초기 구성 | 전체 | 환경 세팅 완료 후 하네스 등록 |
| 2026-06-09 | realtime-developer 추가, WebSocket 범위 편입 | agents/realtime-developer.md, springboot-dev SKILL.md | 채팅 기능 추가 요청 |
| 2026-06-09 | domain-glossary 뼈대 추가, qa-reviewer 컴플라이언스 섹션 추가, backend-analyst glossary 참조 원칙 추가 | references/domain-glossary.md, agents/qa-reviewer.md, agents/backend-analyst.md | 변호사법·보험업법 리스크 대응 |
