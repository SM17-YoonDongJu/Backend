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

Spring Boot 4.0.x(현재 4.0.6) / Java 21 기반 REST API 서버. **전술적 DDD(Tactical DDD)**를 지향하되, 패키지는 **실용적 레이어드 구조**로 구성한다. `domain/` 아래 **Bounded Context(도메인) 우선**으로 나누고, 각 컨텍스트 내부를 `controller / dto / entity / repository / service` 레이어로 구성한다. DDD의 색깔(리치 도메인 모델·VO·불변식)은 **폴더가 아니라 `entity` 안**에서 챙긴다.

```
com.soma.backend
├── domain/<context>/          # Bounded Context (auth, user, adjuster, report, match, chat, payment, subscription)
│   ├── controller/            # REST 컨트롤러 — ResponseEntity<ApiResponse<T>>, 얇게 유지
│   ├── dto/                   # Request / Response (API 계약, snake_case)
│   ├── entity/                # JPA 엔티티(Aggregate Root/Entity) + Value Object(record) — 비즈니스 규칙은 여기
│   ├── repository/            # Spring Data JPA Repository
│   └── service/               # 비즈니스 유스케이스 + @Transactional 경계
├── global/                    # 전 컨텍스트 공통 (config, exception, security)
└── infra/                     # 전역 공유 인프라 (redis, s3, fcm, kafka)
```

**레이어 의존 규칙 (핵심):**
- `controller` → `service`, `dto`(+ 조회용 `entity`). HTTP ↔ 유스케이스 변환만, 얇게.
- `service` → `entity`, `repository`, `dto`. 유스케이스 단위로 `@Transactional` 경계를 갖는다.
- `repository` → `entity`. Spring Data JPA 인터페이스, Aggregate 단위 저장/조회. **조회는 native query 금지 — 동적 조회는 QueryDSL, 그 외는 Spring Data 파생 쿼리·JPQL. 불가피한 경우만 사유 주석을 단 문서화된 예외.**
- `entity` → 아무것도 의존 안 함 (실용적 예외: JPA 애노테이션). Spring Web/Service/Controller 참조 금지.
- **의존 방향은 항상 안쪽(entity)으로.** 바깥이 안을 알고, 안은 바깥을 모른다.

**전술적 패턴 (`entity` 안에서 지킨다):** Aggregate(불변식 경계, 외부는 Root 메서드 통해서만 상태 변경, Aggregate 간 참조는 객체가 아니라 ID로) · Value Object(식별자 없는 불변 값은 `record`로 캡슐화) · 리치 도메인 모델(로직을 `service`가 아니라 엔티티 메서드에, setter 남발 금지) · 트랜잭션(한 트랜잭션 = 한 Aggregate 수정 원칙, 다중 Aggregate는 도메인 이벤트로 결합도 완화) · 유비쿼터스 언어(네이밍은 `.claude/references/domain-glossary.md` 준수).

> **적용 범위:** 신규 코드는 이 구조를 따른다. 구현 상세·예시·안티패턴은 `ddd-tactical` 스킬 참조.

> **Spring Boot 4 주의 (Boot 3와 다름):** JSON 매퍼 기본값은 **Jackson 3(`tools.jackson`)** 다 — 구 `com.fasterxml.jackson...ObjectMapper` 빈은 자동구성되지 않으므로 주입하지 말 것(`tools.jackson.databind.json.JsonMapper` 사용). Nullness 애노테이션은 **JSpecify(`org.jspecify.annotations`)**, 구 `org.springframework.lang.NonNull`은 deprecated. HTTP 422는 `HttpStatus.UNPROCESSABLE_CONTENT`(구 `UNPROCESSABLE_ENTITY` deprecated). 기반은 Spring Framework 7.

**global/security** — `JwtProvider`로 토큰 생성·검증, `JwtFilter`(OncePerRequestFilter)로 요청마다 인증 처리, `CustomUserDetails`에 `userId`와 `role`을 담아 `SecurityContext`에 저장한다. 인증 전송은 **HttpOnly 쿠키 기반**이다 — `JwtFilter`는 `Authorization` 헤더가 아니라 `access_token` 쿠키에서 토큰을 읽고, `CookieProvider`가 `access_token`/`refresh_token` 쿠키를 생성·만료·조회하며(`access_token`은 Path `/`, `refresh_token`은 `/auth`로 좁혀 재발급·로그아웃 요청에만 전송) `AuthTokenService`가 발급을 오케스트레이션한다. `/auth/**`는 access 검증을 건너뛴다(`shouldNotFilter`, 재발급·로그아웃은 refresh 쿠키로 동작). 인증 실패(401)는 `RestAuthenticationEntryPoint`, 인가 거부(403)는 `RestAccessDeniedHandler`가 `ErrorResponse`로 응답한다. 쿠키 인증이라 CORS는 `allowCredentials(true)` + `app.cors.allowed-origin-patterns`(와일드카드 `*` 불가, 패턴 목록)로 구성한다.

**global/exception** — 모든 예외는 `BusinessException(ErrorCode)`으로 던지고 `GlobalExceptionHandler`가 `ErrorResponse` (`{ "status": "400", "code": "ERROR_CODE", "message": "..." }`) 형태로 응답한다.

**infra/redis** — `RefreshTokenRepository`가 `RedisTemplate<String, String>`으로 Refresh Token을 `refresh:{userId}` 키로 관리한다 (TTL은 `jwt.refresh-token-expiry` 재사용, 기본 14일). RTR 재발급은 `rotate(userId, oldToken, newToken)`가 **Lua 스크립트로 `GET`→비교→`SET`(PX)/`DEL`을 원자적 CAS**로 수행한다 — 저장값이 제시한 old 토큰과 일치할 때만 교체(`RotateResult.ROTATED`)하므로 동시 재발급 경쟁 창(race window)이 없다. 불일치(`MISMATCH`, 이미 회전됨·탈취 의심)면 키를 삭제해 토큰을 무효화하고, 저장값 없음은 `NOT_FOUND`(만료·미존재)다. 최초·소셜 로그인은 `save`로 덮어쓴다.

**infra/s3** — `S3Client` Bean은 `infra/s3/S3Config`에서 `aws.*` 프로퍼티로 직접 구성한다 (Spring Cloud AWS 미사용).

## Key Configuration

환경변수는 `.env.example` 참고. 필수값: `DB_PASSWORD`, `JWT_SECRET`, `AWS_*`, `KAKAO_*`, `NAVER_*`.

쿠키 인증·CORS는 프로퍼티로 분리한다(기본값 있어 필수 아님): `COOKIE_SECURE`(기본 `true`, 로컬 http는 `false`), `COOKIE_SAME_SITE`(기본 `Lax`, cross-site 운영은 `None`+https), `CORS_ALLOWED_ORIGIN_PATTERNS`(기본 `http://localhost:3000`, 쉼표 구분 패턴 목록 — 예 `https://앱도메인,https://*.vercel.app`).

로컬 개발 시 DB/Redis 기본값이 적용되므로 `docker compose up -d`만 실행하면 된다.

DB 스키마는 Flyway로 관리한다 (`src/main/resources/db/migration/V{n}__{description}.sql`). JPA `ddl-auto`는 `validate`로 고정.

## Git Conventions

- 커밋 메시지는 **항상 한국어**로 작성한다.
- 형식: `<type>(<scope>): <한국어 설명>` (Conventional Commits 준수)
- 예시: `feat(auth): 카카오 OAuth2 소셜 로그인 구현`, `fix(match): 매칭 수락 시 중복 채팅방 생성 버그 수정`

## Branch Strategy

Git Flow (경량화) 전략을 사용한다.

```
main      ← 운영 배포 (태그로 버전 관리, 직접 push 금지)
develop   ← 통합 브랜치, 스테이징 배포 대상 (직접 push 금지)
feature/* ← 이슈별 기능 개발 (develop 기준으로 분기)
hotfix/*  ← 운영 긴급 수정 (main 기준으로 분기)
```

**플로우:**
- `feature/<issue>-<name>` → PR → `develop` (기능 PR, 1인 이상 approve)
- `develop` → PR → `main` (릴리즈 PR, 태그 `v0.x.0` 부여)
- `hotfix/<issue>-<name>` → PR → `main` (긴급 수정 후 `develop`에도 머지)

**브랜치 네이밍:** `<type>/<issue-number>-<2-3-word-kebab-summary>`
예시: `feat/12-kakao-oauth2`, `bug/17-match-duplicate-room`

## Code Conventions

Checkstyle(`config/checkstyle/checkstyle.xml`)가 강제하는 규칙 — 위반 시 빌드 실패.

**포맷**
- 들여쓰기: 스페이스 2칸 (탭 금지)
- 최대 줄 길이: 120자 (package·import·URL 제외)
- 파일 마지막 줄: 빈 줄 필수

**네이밍**
- 클래스: `PascalCase`
- 메서드·파라미터·지역변수·필드: `camelCase`
- 상수(`static final`): `UPPER_SNAKE_CASE`
- 패키지: 소문자, 숫자·언더스코어 금지
- 파라미터·변수명 1글자 금지 (최소 2자, 예: `catch (Exception ex)`)

**임포트**
- 와일드카드 임포트(`*`) 금지
- 미사용·중복 임포트 금지
- 그룹 순서: `java` → `javax` → `org` → `net` → `com`(외부) → 기타(`io`·`jakarta`·`lombok` 등) → `com.soma`(자사), 그룹 간 빈 줄 1개·그룹 내 알파벳 정렬

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
- **쿼리 작성 규칙:** 조회에 native query(`nativeQuery = true`)를 쓰지 않는다. 동적 조회(필터·정렬·페이지네이션)는 QueryDSL(`JPAQueryFactory` + Q타입, `*RepositoryCustom`/`*RepositoryImpl` 프래그먼트, projection은 record + `Projections.constructor`), 단순 조회·카운트는 Spring Data 파생 쿼리나 JPQL(`@Query`)로 작성한다. 아직 엔티티로 매핑되지 않은 테이블을 조인하는 읽기 전용 projection처럼 QueryDSL/JPQL로 표현할 수 없는 경우에만, 리포지토리 코드에 사유를 주석으로 남긴 '문서화된 예외'로 native를 허용한다.

## Spring Boot 담당 범위

FastAPI가 담당하는 영역 (Spring Boot 범위 외):
- AI 챗봇 WebSocket
- OCR 실행, LangGraph 멀티에이전트, RAG (AI 리포트 생성 파이프라인)
- Kafka consumer 측 내부 처리 (OCR 트리거 메시지 소비 이후)

Spring Boot가 담당하는 영역:
- 인증·회원 (JWT, OAuth2, RBAC)
- 사고 상황 입력 수신 + 진단서 S3 업로드 + OCR 트리거 Kafka producer 발행 (리포트 생성 요청의 진입점)
- 손해사정사 매칭 플로우 (요청·수락)
- 검수 리포트 등록(서명 포함 PATCH), review_feedback 수집
- 구독·결제 (PG사 연동)
- FCM Push (검수 완료 시)
- WebSocket(STOMP) 채팅 (ChatRoom, ChatMessage, 오프라인 FCM 푸시)

> **OCR 처리 경계:** Spring Boot가 사고 정보·진단서를 받아 S3에 저장하고 Kafka로 OCR 트리거 메시지를 **발행(producer)**한다. FastAPI가 이 메시지를 **소비(consumer)**하여 OCR·AI 리포트 생성을 수행한다. OCR 알고리즘 자체는 Spring 범위 외.

## 하네스: Spring Boot Backend

**목표:** 전문 에이전트 팀으로 Spring Boot 피처를 분석·구현·검증한다.

**트리거:** 피처 구현, API 추가, 버그 수정, 채팅/WebSocket 작업 요청 시 `springboot-dev` 스킬을 사용하라. 단순 질문은 직접 응답 가능.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-06-09 | 초기 구성 | 전체 | 환경 세팅 완료 후 하네스 등록 |
| 2026-06-09 | realtime-developer 추가, WebSocket 범위 편입 | agents/realtime-developer.md, springboot-dev SKILL.md | 채팅 기능 추가 요청 |
| 2026-06-09 | domain-glossary 뼈대 추가, qa-reviewer 컴플라이언스 섹션 추가, backend-analyst glossary 참조 원칙 추가 | references/domain-glossary.md, agents/qa-reviewer.md, agents/backend-analyst.md | 변호사법·보험업법 리스크 대응 |
| 2026-06-20 | OCR 처리 경계 재정의 — 사고 입력 수신·진단서 S3 업로드·OCR 트리거 Kafka producer를 Spring 범위로 편입 (OCR 실행/Kafka consumer는 FastAPI 유지) | CLAUDE.md 담당 범위, springboot-dev SKILL.md, agents/backend-developer.md, agents/backend-analyst.md, references/domain-glossary.md | 사고 정보 입력~OCR 트리거 구간 Spring 담당 결정 |
| 2026-07-02 | infra-developer 에이전트 + spring-infra 스킬 추가, 인프라·관측성·배포 하드닝 영역 편입 (actuator·JVM/GC·DB풀·Kafka producer 배선·docker·PII 로깅·smoke test) | agents/infra-developer.md, skills/spring-infra/SKILL.md, springboot-dev SKILL.md | 프로덕션 하드닝 + 로컬 Kafka(docker compose) 작업에 홈이 없어 전담 에이전트/스킬 신설 |
| 2026-07-02 | 아키텍처를 레이어드 → 전술적 DDD로 전환 (규칙·하네스만, 코드는 점진 마이그레이션), ddd-tactical 스킬 신설 | CLAUDE.md Architecture, skills/ddd-tactical/SKILL.md, agents/backend-analyst.md, agents/backend-developer.md | DDD 기반 개발 체계 도입 결정 |
| 2026-07-02 | 패키지 구조를 4계층(domain/application/presentation/infrastructure)에서 실용적 레이어드(`domain/<context>/{controller,dto,entity,repository,service}`)로 단순화. DDD 색깔(리치 모델·VO·불변식)은 `entity` 안에서 유지 | CLAUDE.md Architecture, skills/ddd-tactical/SKILL.md | 전술 DDD 이점은 유지하되 폴더 4계층 과함 → 실용적 5-패키지로 합의 |
| 2026-07-13 | 조회 쿼리 규칙 신설(동적=QueryDSL, 단순=Spring Data 파생/JPQL, native는 문서화된 예외만) + 코드-하네스 동기화: qa-reviewer enum 정정(REPORTS.status `MATCHED`→`CLOSED`, accident_type 영문)·native 리뷰 항목 추가, spring-qa 리포지토리/QueryDSL 테스트를 @SpringBootTest/실제 test_db로 정정(@DataJpaTest·TestContainers 미사용), backend-developer에 쿼리·객체생성 원칙 추가, domain-glossary 상태머신 종료상태 `MATCHED`→`CLOSED` 동기화 | CLAUDE.md, agents/backend-developer·qa-reviewer, skills/ddd-tactical·spring-qa, references/domain-glossary, harness.md | native→QueryDSL 리팩터(#100) 후 drift 감사·동기화 |
| 2026-07-10 | RefreshToken RTR을 `save` 덮어쓰기·비원자적 `getAndDelete`에서 **Lua 원자적 CAS `rotate`**(저장값==oldToken일 때만 교체, `RotateResult` ROTATED/NOT_FOUND/MISMATCH, 불일치 시 키 삭제=재사용·탈취 탐지)로 변경 반영. 재발급 서비스는 서명·만료 검증(1단계)과 Redis 원자 회전(2단계)으로 분리 | CLAUDE.md infra/redis, skills/spring-security-impl(SKILL.md·references/jwt-impl.md), agents/security-developer.md | 동시 재발급 경쟁 창 제거 + 토큰 재사용 탐지 강화 (코드 선반영 → 하네스 동기화) |
| 2026-07-10 | spring-security-impl 스킬을 **HttpOnly 쿠키 인증 + 수동 REST OAuth** 현행 구조로 동기화 — 헤더(Bearer)·바디 토큰·`oauth2Login`·리다이렉트-쿼리토큰 서술 제거, `access_token`/`refresh_token` 쿠키·`JwtFilter`(쿠키 우선, `/auth/**` shouldNotFilter)·`CookieProvider`·`AuthTokenService`·`OAuthLoginService`+`SignupTicket`+`AuthRegisterService`·`allowedOriginPatterns` CORS·Boot 4로 갱신 | skills/spring-security-impl(SKILL.md·references/jwt-impl.md·references/oauth2-providers.md) | 스킬이 헤더/바디·Spring oauth2Login 가정으로 stale → 실제 쿠키 기반 구현과 정합 |
| 2026-07-10 | 쿠키 Path 스코핑 반영 — `refresh_token` 쿠키를 Path `/auth`로 좁혀(재발급·로그아웃에만 전송) 노출 표면 축소, `access_token`은 Path `/` 유지. "쿠키는 Path `/`로 발급" 단언(stale) 정정 | CLAUDE.md global/security, skills/spring-security-impl(SKILL.md·references/jwt-impl.md) | 코드 선반영(CookieProvider Path 분리) → 하네스 동기화 |
