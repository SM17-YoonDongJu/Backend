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

Spring Boot 3.4.x/ Java 21 기반 REST API 서버. **전술적 DDD(Tactical DDD)**를 지향하되, 패키지는 **실용적 레이어드 구조**로 구성한다. `domain/` 아래 **Bounded Context(도메인) 우선**으로 나누고, 각 컨텍스트 내부를 `controller / dto / entity / repository / service` 레이어로 구성한다. DDD의 색깔(리치 도메인 모델·VO·불변식)은 **폴더가 아니라 `entity` 안**에서 챙긴다.

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
- `repository` → `entity`. Spring Data JPA 인터페이스, Aggregate 단위 저장/조회.
- `entity` → 아무것도 의존 안 함 (실용적 예외: JPA 애노테이션). Spring Web/Service/Controller 참조 금지.
- **의존 방향은 항상 안쪽(entity)으로.** 바깥이 안을 알고, 안은 바깥을 모른다.

**전술적 패턴 (`entity` 안에서 지킨다):** Aggregate(불변식 경계, 외부는 Root 메서드 통해서만 상태 변경, Aggregate 간 참조는 객체가 아니라 ID로) · Value Object(식별자 없는 불변 값은 `record`로 캡슐화) · 리치 도메인 모델(로직을 `service`가 아니라 엔티티 메서드에, setter 남발 금지) · 트랜잭션(한 트랜잭션 = 한 Aggregate 수정 원칙, 다중 Aggregate는 도메인 이벤트로 결합도 완화) · 유비쿼터스 언어(네이밍은 `.claude/references/domain-glossary.md` 준수).

> **적용 범위:** 신규 코드는 이 구조를 따른다. 구현 상세·예시·안티패턴은 `ddd-tactical` 스킬 참조.

**global/security** — `JwtProvider`로 토큰 생성·검증, `JwtFilter`(OncePerRequestFilter)로 요청마다 인증 처리, `CustomUserDetails`에 `userId`와 `role`을 담아 `SecurityContext`에 저장한다.

**global/exception** — 모든 예외는 `BusinessException(ErrorCode)`으로 던지고 `GlobalExceptionHandler`가 `ErrorResponse` (`{ "status": "400", "code": "ERROR_CODE", "message": "..." }`) 형태로 응답한다.

**infra/redis** — `RefreshTokenRepository`가 `RedisTemplate<String, String>`으로 Refresh Token을 `refresh:{userId}` 키로 관리한다 (TTL 30일).

**infra/s3** — `S3Client` Bean은 `infra/s3/S3Config`에서 `aws.*` 프로퍼티로 직접 구성한다 (Spring Cloud AWS 미사용).

## Key Configuration

환경변수는 `.env.example` 참고. 필수값: `DB_PASSWORD`, `JWT_SECRET`, `AWS_*`, `KAKAO_*`, `NAVER_*`.

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
