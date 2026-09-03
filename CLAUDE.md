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
├── domain/<context>/          # Bounded Context (auth, user, adjuster, report, chat, notification, upload)
│   ├── controller/            # REST 컨트롤러 — ResponseEntity<ApiResponse<T>>, 얇게 유지
│   ├── dto/                   # Request / Response (API 계약, snake_case)
│   ├── entity/                # JPA 엔티티(Aggregate Root/Entity) + Value Object(record) — 비즈니스 규칙은 여기
│   ├── repository/            # Spring Data JPA Repository
│   └── service/               # 비즈니스 유스케이스 + @Transactional 경계
├── domain/common/             # 공유 기반 (BaseEntity, JpaConfig) — 컨텍스트 아님
├── global/                    # 전 컨텍스트 공통 (config, exception, response, security)
└── infra/                     # 전역 공유 인프라 (redis, s3, fcm, sqs, outbox)
```

> **도메인 현황(문서-코드 정합):** 실제 구현된 컨텍스트는 `auth·user·adjuster·report·chat·notification` + S3 presigned 업로드 파사드 `upload`(POST /uploads, Aggregate 없는 얇은 컨텍스트) + 공유 기반 `common`이다. `match`는 빈 placeholder이며 **매칭(제안 요청·수락) 로직은 현재 `report` 도메인(proposal)에 있다**. `payment`·`subscription`은 **계획된 컨텍스트로 아직 미구현**(예약 에러코드 `PAYMENT_FAILED`·`SUBSCRIPTION_NOT_FOUND`만 존재).

**레이어 의존 규칙 (핵심):**
- `controller` → `service`, `dto`(+ 조회용 `entity`). HTTP ↔ 유스케이스 변환만, 얇게.
- `service` → `entity`, `repository`, `dto`. 유스케이스 단위로 `@Transactional` 경계를 갖는다.
- `repository` → `entity`. Spring Data JPA 인터페이스, Aggregate 단위 저장/조회. **조회는 native query 금지 — 동적 조회는 QueryDSL, 그 외는 Spring Data 파생 쿼리·JPQL. 불가피한 경우만 사유 주석을 단 문서화된 예외.**
- `entity` → 아무것도 의존 안 함 (실용적 예외: JPA 애노테이션). Spring Web/Service/Controller 참조 금지.
- **의존 방향은 항상 안쪽(entity)으로.** 바깥이 안을 알고, 안은 바깥을 모른다.

**전술적 패턴 (`entity` 안에서 지킨다):** Aggregate(불변식 경계, 외부는 Root 메서드 통해서만 상태 변경, Aggregate 간 참조는 객체가 아니라 ID로) · Value Object(식별자 없는 불변 값은 `record`로 캡슐화) · 리치 도메인 모델(로직을 `service`가 아니라 엔티티 메서드에, setter 남발 금지) · 트랜잭션(한 트랜잭션 = 한 Aggregate 수정 원칙, 다중 Aggregate는 도메인 이벤트로 결합도 완화) · 유비쿼터스 언어(네이밍은 `.claude/references/domain-glossary.md` 준수).

> **적용 범위:** 신규 코드는 이 구조를 따른다. 구현 상세·예시·안티패턴은 `ddd-tactical` 스킬 참조.

> **Spring Boot 4 주의 (Boot 3와 다름):** JSON 매퍼 기본값은 **Jackson 3(`tools.jackson`)** 다 — 구 `com.fasterxml.jackson...ObjectMapper` 빈은 자동구성되지 않으므로 주입하지 말 것(`tools.jackson.databind.json.JsonMapper` 사용). Nullness 애노테이션은 **JSpecify(`org.jspecify.annotations`)**, 구 `org.springframework.lang.NonNull`은 deprecated. HTTP 422는 `HttpStatus.UNPROCESSABLE_CONTENT`(구 `UNPROCESSABLE_ENTITY` deprecated). 기반은 Spring Framework 7. **springdoc-openapi(3.0.3)는 이 Jackson 3 설정을 못 본다** — springdoc·swagger-core는 스키마를 만들 때 Spring 컨텍스트의 `ObjectMapper` 빈을 전혀 조회하지 않고 swagger-core 자체의 정적 싱글턴(`io.swagger.v3.core.util.Json31.mapper()`, OpenAPI 3.1 생성 시 대상)을 그대로 쓴다(`ModelConverters`가 항상 `new ModelResolver(Json31.mapper())`로 고정). Spring Boot 4는 Jackson 3만 자동구성해 이 정적 매퍼엔 `spring.jackson.property-naming-strategy: SNAKE_CASE`가 반영될 수 없으므로, 방치하면 실제 응답은 snake_case인데 `/v3/api-docs` 스키마 프로퍼티명만 camelCase로 어긋난다. `OpenApiConfig`의 `@PostConstruct`에서 `Json31.mapper().setPropertyNamingStrategy(...)`로 그 정적 싱글턴을 직접 맞춰야 한다(Spring `@Bean ObjectMapper` 등록은 무시됨) — 문서 생성 전용이라 런타임 직렬화(Jackson 3)엔 영향 없다.

**global/security** — `JwtProvider`로 토큰 생성·검증, `JwtFilter`(OncePerRequestFilter)로 요청마다 인증 처리, `CustomUserDetails`에 `userId`와 `role`을 담아 `SecurityContext`에 저장한다. 인증 전송은 **HttpOnly 쿠키 기반**이다 — `JwtFilter.resolveToken()`은 `Authorization: Bearer` 헤더를 우선 확인하고 없으면 `access_token` 쿠키로 폴백한다(운영은 쿠키 경로, 헤더는 호환용). `CookieProvider`가 `access_token`/`refresh_token` 쿠키를 생성·만료·조회하며(`access_token`은 Path `/`, `refresh_token`은 `/auth`로 좁혀 재발급·로그아웃 요청에만 전송) `AuthTokenService`가 발급을 오케스트레이션한다. `JwtFilter`는 블랙리스트(`TokenBlacklistRepository`)에 오른 토큰을 거부한다(로그아웃 시 무효화). `/auth/**`는 access 검증을 건너뛴다(`shouldNotFilter`, 재발급·로그아웃은 refresh 쿠키로 동작). 인증 실패(401)는 `RestAuthenticationEntryPoint`, 인가 거부(403)는 `RestAccessDeniedHandler`가 `ErrorResponse`로 응답한다. 쿠키 인증이라 CORS는 `allowCredentials(true)` + `app.cors.allowed-origin-patterns`(와일드카드 `*` 불가, 패턴 목록)로 구성한다.

**global/exception** — 모든 예외는 `BusinessException(ErrorCode)`으로 던지고 `GlobalExceptionHandler`가 `ErrorResponse` (`{ "status": "400", "code": "ERROR_CODE", "message": "..." }`) 형태로 응답한다.

**infra/redis** — `RefreshTokenRepository`가 `RedisTemplate<String, String>`으로 Refresh Token을 `refresh:{userId}` 키로 관리한다 (TTL은 `jwt.refresh-token-expiry` 재사용, 기본 14일). RTR 재발급은 `rotate(userId, oldToken, newToken)`가 **Lua 스크립트로 `GET`→비교→`SET`(PX)/`DEL`을 원자적 CAS**로 수행한다 — 저장값이 제시한 old 토큰과 일치할 때만 교체(`RotateResult.ROTATED`)하므로 동시 재발급 경쟁 창(race window)이 없다. 불일치(`MISMATCH`, 이미 회전됨·탈취 의심)면 키를 삭제해 토큰을 무효화하고, 저장값 없음은 `NOT_FOUND`(만료·미존재)다. 최초·소셜 로그인은 `save`로 덮어쓴다.

**infra/s3** — `S3Client`·`S3Presigner` Bean은 `infra/s3/S3Config`에서 구성한다 — 리전만 `aws.region` 프로퍼티로 주입하고 자격증명은 `DefaultCredentialsProvider`(IAM Role·`~/.aws`)로 위임한다 (Spring Cloud AWS 미사용). presigned URL은 채팅 첨부 다운로드에 사용한다.

**infra/outbox** — 트랜잭셔널 아웃박스 패턴. 도메인 트랜잭션과 같은 커밋으로 이벤트를 적재하고, 별도 스케줄러가 `FOR UPDATE SKIP LOCKED`로 폴링해 처리한다 (부수효과의 원자성·재시도 보장, 다중 인스턴스 중복 처리 방지). 용도가 다른 **두 아웃박스가 공존**한다 — `outbox_events`(`OutboxEvent`)는 `OutboxProcessor`가 회원 탈퇴 후처리(Redis 토큰 정리·Apple 토큰 폐기)를 수행하고, `kafka_outbox_events`(`OcrOutboxEvent`)는 `OutboxRelay`가 OCR 트리거를 SQS로 발행한다. 발행 대상 큐는 아웃박스 `topic` 컬럼(=SQS 큐 이름, `app.sqs.ocr-queue-name`)이며 브로커는 관리형 AWS SQS다. 큐 이름에 **폴백 기본값을 두지 않는다** — env가 빠지면 없는 큐 이름이 행에 박제돼 나중에 고쳐도 복구되지 않기 때문이다(기동 실패로 즉시 노출). 발행 결과는 로그와 메트릭(`outbox.relay.sent`·`outbox.relay.failed`, 태그 `queue`) 양쪽에 남는다. **클래스는 `OcrOutbox*`인데 테이블은 `kafka_outbox_events`(V13)로 남아 있다** — 브로커 전환(#208) 후 클래스만 용도 기준으로 정리했고, 테이블 리네임은 `ALTER TABLE ... RENAME TO`가 `public` 스키마 `CREATE` 권한을 요구해(운영 유저에 없음, 아래 Key Configuration 참조) 보류했다.

## Key Configuration

환경변수는 `.env.example` 참고. 필수값: `DB_PASSWORD`, `JWT_SECRET`, `S3_BUCKET`, `KAKAO_*`, `NAVER_*`, `SQS_OCR_QUEUE_NAME`(폴백 없음 — 미설정 시 기동 실패. `local` 프로파일은 자체 명시라 예외). AWS 자격증명(access/secret key)은 IAM Role 자동 탐색(`DefaultCredentialsProvider`)에 위임하므로 env 필수값이 아니며, `AWS_REGION`은 기본값이 있다.

쿠키 인증·CORS는 프로퍼티로 분리한다(기본값 있어 필수 아님): `COOKIE_SECURE`(기본 `true`, 로컬 http는 `false`), `COOKIE_SAME_SITE`(기본 `Lax`, cross-site 운영은 `None`+https), `CORS_ALLOWED_ORIGIN_PATTERNS`(기본 `http://localhost:3000`, 쉼표 구분 패턴 목록 — 예 `https://앱도메인,https://*.vercel.app`).

로컬 개발 시 DB/Redis 기본값이 적용되므로 `docker compose up -d`만 실행하면 된다.

DB 스키마는 Flyway로 관리한다 (`src/main/resources/db/migration/V{n}__{description}.sql`). JPA `ddl-auto`는 `validate`로 고정.

**신규 마이그레이션 번호는 `develop` HEAD뿐 아니라 그 시점에 열려 있는 다른 PR과도 충돌할 수 있다.** 각자 브랜치 기준으로 "다음 번호"를 잡기 때문에, 두 PR이 동시에 같은 `V{n}`을 쓰는 경우가 생긴다(PR #251·#249가 둘 다 `V43`을 쓴 사례). 새 마이그레이션을 추가하기 전에 `develop`의 최신 번호뿐 아니라 열려 있는 PR들의 마이그레이션 파일명도 확인할 것 — 충돌이 발견되면 **나중에 머지되는 쪽이 재번호**한다(먼저 머지되는 PR의 번호는 그대로 둔다).

**신규 마이그레이션이 새 테이블을 만들 때는 반드시 `CREATE TABLE core.테이블명`으로 스키마를 명시한다.**

**가장 중요한 이유는 권한이 아니라 앱이 테이블을 찾지 못한다는 것이다.** dev·prod의 앱 테이블은 전부 **`core` 스키마에 있고**(`public`에는 `flyway_schema_history`만 남아 있다), DB 유저 `app_owner`의 `search_path`가 `core, public`이라 **Hibernate가 인식하는 기본 스키마는 `core`다**(기동 로그 `Default catalog/schema: <db>/core`). 그런데 Flyway는 `default-schema: public` 설정 때문에 마이그레이션 실행 시 `public`을 앞에 붙인 `search_path`로 동작한다 — 즉 **스키마 미지정 `CREATE TABLE`은 `public`에 만들어지고, 앱은 `core`에서 찾으므로 못 찾는다.** 결과는 `ddl-auto: validate` 실패로 **앱이 기동하지 못하는 것**이다. 권한이 있는 환경(prod은 `public` `CREATE`가 열려 있다)에서는 **마이그레이션이 조용히 성공한 뒤 배포가 부팅 단계에서 죽으므로 더 위험하다.**

권한 문제도 겹친다. `app_owner`는 `core`에는 `CREATE` 권한이 있지만 dev의 `public`에는 없다(PostgreSQL 15+부터 `public` 스키마 `CREATE`가 `PUBLIC` 롤에서 기본 제거됨). `application.yml`의 `flyway.schemas: core, public` / `default-schema: public` 설정은 Flyway가 `core`를 알게 하고 기존 이력 테이블(`flyway_schema_history`, `public` 고정)을 계속 찾게 해줄 뿐, 스키마 미지정 `CREATE TABLE`을 `core`로 보내주지 않는다. (V33이 이 규칙을 안 지켜 배포 실패했고 V35로 `core`로 재이관한 사고가 있었다 — 이슈 #223.)

**스키마 배치는 V40이 통일한다.** 기존 마이그레이션(V1~V39)이 스키마 미지정이라 빈 DB에서는 테이블이 `public`에 생기는데, `V40__move_public_tables_to_core.sql`이 `public`에 남은 테이블·시퀀스를 `core`로 옮긴다(`flyway_schema_history` 제외 — Flyway `default-schema`가 가리키는 위치라 옮기면 이력을 못 찾는다). dev·prod는 이미 `core`라 **no-op**이고, 신규 환경에서만 실제로 이관한다. `SET SCHEMA`는 대상 스키마 `CREATE` 권한만 요구하므로 `public`에 권한이 없는 dev에서도 통과한다. 검증: 신규 환경(빈 DB→V1~V40)·prod 리허설(V32+`core`→V33~V40)·dev 조건(V39+`core`+`public` 권한 없음→V40) 세 경우 모두 기동 확인.

**앱의 스키마 해석은 JDBC URL로 고정한다.** `application.yml`의 datasource URL에 `?currentSchema=core,public`을 명시한다 — 이걸로 Hibernate 기본 스키마가 `core`로 고정되고(`validate` 대상), `EncryptionKeyStore`의 스키마 미지정 원시 JDBC도 `core.encryption_keys`를 찾는다. 명시하지 않으면 DB 롤의 `search_path` 설정에 의존하게 되는데, 그건 레포 밖 상태라 롤 재생성·RDS 복원으로 사라지면 앱이 조용히 깨진다. `core`가 아직 없는 DB에서는 `public`으로 폴백하므로 안전하다.

> **신규 환경 부트스트랩:** 완전히 빈 DB를 세울 때는 V1~V33이 `public`에 테이블을 만들므로 **초기 1회 `public` `CREATE` 권한이 필요**하다(이후 V40이 `core`로 옮긴다). 정상 운영 상태에서는 필요 없다 — 신규 마이그레이션은 `core.` 명시 규칙을 따르므로.

**주의: `public` `CREATE` 권한을 요구하는 DDL은 `CREATE TABLE`만이 아니다.** 이름 해석(어느 스키마의 테이블을 가리키는가)과 권한(그 DDL을 실행할 수 있는가)은 별개 문제다 — `search_path`가 테이블을 찾아준다고 해서 DDL이 통과하는 건 아니다. PostgreSQL 16 실측 기준:

| DDL | `public` CREATE 권한 없을 때 |
|-----|------------------------------|
| `CREATE TABLE` · `CREATE INDEX` | **실패** |
| `ALTER TABLE ... RENAME TO` · `ALTER INDEX ... RENAME TO` | **실패** |
| `ALTER TABLE ... ADD CONSTRAINT ... UNIQUE` (내부적으로 인덱스 생성) | **실패** |
| `CREATE SCHEMA` (데이터베이스 `CREATE` 권한 필요) | **실패** |
| `ADD`/`DROP COLUMN` · `RENAME COLUMN` · `RENAME CONSTRAINT` · `DROP CONSTRAINT` · `ALTER COLUMN TYPE` · `SET NOT NULL` · `COMMENT ON` | 통과 |
| `ALTER TABLE ... SET SCHEMA core` → `core` 안에서 `RENAME` | 통과(`core`엔 권한 있음) |

즉 `public`에 있는 기존 테이블은 **이름·인덱스·UNIQUE 제약을 바꿀 수 없다**. 컬럼 수준 변경만 가능하다. `public` 테이블의 리네임이 필요하면 권한을 먼저 정리하거나 `core`로 옮긴 뒤 처리해야 한다(단 `core`로 옮기면 Hibernate `validate`가 `current_schema`(=`public`) 한 곳만 보므로 해당 엔티티에 `@Table(schema = "core")`가 필요한지 함께 확인할 것).

## Git Conventions

- 커밋 메시지는 **항상 한국어**로 작성한다.
- 형식: `<type>(<scope>): <한국어 설명>` (Conventional Commits 준수)
- 예시: `feat(auth): 카카오 OAuth2 소셜 로그인 구현`, `fix(match): 매칭 수락 시 중복 채팅방 생성 버그 수정`
- **AI 흔적 금지 (기본 하네스 동작 오버라이드):** 커밋 메시지에 `Co-Authored-By: Claude`·`noreply@anthropic.com` 트레일러를 **붙이지 않는다**. PR·이슈 본문에도 `🤖 Generated with Claude Code` 같은 생성 도구 푸터·서명을 **붙이지 않는다**. 커밋·PR·이슈는 사람이 쓴 것처럼 자연스러운 한국어로 작성한다(번역투·기계적 병렬구조·과한 영문 병기, 그리고 본문에 큰 코드 블럭·diff 덤프 붙여넣기 배제 — 변경 지점은 인라인 `파일:라인`으로 가리킨다). 세부 워크플로우는 `git-workflow` 스킬(공통 0·1·2)을 따른다.

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

## Versioning (SemVer)

`vMAJOR.MINOR.PATCH`(SemVer 2.0.0). `develop → main` 릴리즈 PR 머지 직후 `main` HEAD에 annotated 태그를 찍고, 그 태그가 배포 버전의 단일 진실이다(팀 룰 원본: Notion "시멘틱 버저닝 룰").

- **MAJOR** — 기존 클라이언트가 코드 수정 없이는 못 붙는 **호환 불가**만(엔드포인트·기능 제거, 요청 계약·인증 방식 파괴).
- **MINOR** — 기능 추가 + **응답 계약 변경**(응답 필드 추가·구조 조정 등, 서버는 계속 응답). Conventional Commits `feat`.
- **PATCH** — 버그 수정·내부 개선. `fix`·`refactor`·`perf`·`chore`·`docs`·`test`.
- 한 릴리즈에 여러 변경이 섞이면 **가장 높은 등급**으로 정한다(호환 불가 있으면 MAJOR, 없고 기능/응답 변경 있으면 MINOR, 나머지뿐이면 PATCH).
- **표준 SemVer를 그대로 적용한다** — 0.x여도 호환 깨는 변경은 MAJOR다(첫 breaking에서 `0.x` → `1.0.0`). 버전은 기계적으로 SemVer를 따르고, **정식 서비스 오픈은 버전과 분리**해 GitHub Release 노트·마일스톤으로 표시한다(`1.0.0`을 정식 오픈 예약 버전으로 두지 않는다).
- API URL 버전(`/api/v1`)은 앱 태그와 별개 — 계약 파괴로 구버전 병행이 필요할 때만 `/v2`. `build.gradle`은 `0.0.1-SNAPSHOT` 유지(릴리즈 진실은 git 태그), 첫 릴리즈는 `v0.1.0`.
- **태깅은 semantic-release가 자동화한다** — `deploy-prod.yml`의 `release` job이 `main` push마다 커밋을 분석해 다음 버전을 정하고 태그·GitHub Release·CHANGELOG를 생성한다. 버전 매핑은 `.releaserc.json`의 `releaseRules`에 있다(`feat`→MINOR, `fix`→PATCH, `BREAKING CHANGE`→MAJOR는 preset 기본, 내부 개선 타입 `refactor`·`perf`·`chore`·`docs`·`test`는 PATCH로 끌어올림). 응답 계약 변경을 MINOR로 반영하려면 반드시 `feat`로 커밋해야 한다(분석은 커밋 type만 본다).
- semantic-release는 마지막 태그 이후를 분석하므로 baseline이 필요하다 — **첫 태그 `v0.1.0`만 수동으로 선점**한다.

```bash
# 최초 1회만: baseline 태그 선점 (이후 릴리즈 태깅은 semantic-release가 자동)
git checkout main && git pull
git tag -a v0.1.0 -m "release: v0.1.0"
git push origin v0.1.0
```

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
- **OpenAPI 문서화 규칙:** 컨트롤러가 주고받는 요청·응답 DTO(record) 필드 중 계약상 항상 존재해야 하는 필드에는 `@Schema(requiredMode = Schema.RequiredMode.REQUIRED)`를, 값이 `null`일 수 있는 필드에는 `@Schema(nullable = true)`를 붙인다. springdoc-openapi 3.0.3(Spring Boot 4 라인)은 OpenAPI 3.1을 생성하므로 nullable은 구 OAS 3.0의 `nullable: true` 플래그가 아니라 JSON Schema 표준 유니온 타입(`"type": ["string", "null"]`)으로 렌더링된다 — 애노테이션 문법은 그대로 쓰고 변환은 swagger-core가 담당한다. enum 값에 따라 다른 필드가 필수가 되는 조건부 필수처럼 OpenAPI 표준으로 표현할 수 없는 제약은 `@Schema(description = ...)`에 조건을 서술해 보완한다. `infra/**`의 내부 전달용 DTO(Redis pub/sub 페이로드 등 컨트롤러에 노출되지 않는 타입)는 대상에서 제외한다. **알려진 한계:** swagger-core 2.2.47은 필드 타입이 다른 record(= `$ref`로 참조되는 중첩 객체)일 때 `nullable = true`를 정확한 `anyOf`가 아니라 `{"type": "null", "$ref": "..."}`(의미상 모순)로 렌더링한다 — `anyOf = {...}` 조합으로도 우회되지 않는 라이브러리 한계이니, 원시·배열·enum 필드의 nullable만 신뢰하고 중첩 객체 필드는 필드 설명(`description`)으로 null 조건을 보완한다.

## Spring Boot 담당 범위

FastAPI가 담당하는 영역 (Spring Boot 범위 외):
- AI 챗봇 WebSocket
- OCR 실행, LangGraph 멀티에이전트, RAG (AI 리포트 생성 파이프라인)
- SQS consumer 측 내부 처리 (OCR 트리거 메시지 소비 이후)

Spring Boot가 담당하는 영역:
- 인증·회원 (JWT, OAuth2, RBAC)
- 사고 상황 입력 수신 + 진단서 S3 업로드 + OCR 트리거 SQS producer 발행 (리포트 생성 요청의 진입점)
- 손해사정사 매칭·상담 플로우 (제안 요청·수락·거절 — 현재 report/chat 도메인의 proposal로 구현, 별도 match 도메인 아님)
- 검수 리포트 등록(서명 포함 PATCH), review_feedback 수집
- 구독·결제 (PG사 연동) — **계획, 아직 미구현** (예약 에러코드만 존재)
- FCM Push + 인앱 알림 (notification 도메인, 검수 완료 등)
- WebSocket(STOMP) 채팅 (ChatRoom, ChatMessage, 오프라인 FCM 푸시)

> **OCR 처리 경계:** Spring Boot가 사고 정보·진단서를 받아 S3에 저장하고 SQS로 OCR 트리거 메시지를 **발행(producer)**한다. FastAPI가 이 메시지를 **소비(consumer)**하여 OCR·AI 리포트 생성을 수행한다. OCR 알고리즘 자체는 Spring 범위 외.

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
| 2026-07-14 | 사정사 홈 대시보드 API(GET /adjusters/me/home)를 report → **adjuster 도메인**으로 분리. `adjuster_profiles`를 `AdjusterProfile` 엔티티로 매핑하고 남은 native `findAdjusterIdentity`를 QueryDSL로 전환(문서화된 예외 1건 제거), 홈 크로스-애그리거트 조회를 `AdjusterHomeRepository`로 자립화. ERD 정합: `adjuster_profiles.registration_url·updated_at` 추가(V12), glossary ADJUSTER_PROFILES/APPLICATIONS 필드·상태 정정(`speciality`→`specialties[]`, `ACCEPTED`→`APPROVED`). **지역 배열화**: `users.region`·`adjuster_profiles.activity_region`을 `text[]`로 전환(V13) — 복수 지역 지원, 검수대기 지역 필터를 `array_contains`로 변경 | domain/adjuster/*, V12·V13 마이그레이션, user·report 도메인 region 필드, references/domain-glossary.md | #100 native→QueryDSL 리팩터 중 adjuster 도메인 분리 + 기존 엔티티 ERD 반영(지역 배열화) 요청 |
| 2026-07-20 | **하네스 전반 코드 정합 감사·동기화** (5스트림 병렬 감사 → 3스트림 병렬 수정, 총 드리프트 62건 반영, src 무변경). CLAUDE.md: 도메인 목록에서 유령 컨텍스트 payment·subscription 제거(계획/미구현 표기)·빈 match(→report proposal)·누락 common/notification 반영, infra/outbox 신설, global/response 반영, JwtFilter를 "Bearer 헤더 우선·access_token 쿠키 폴백"으로 정정, S3(DefaultCredentialsProvider)·env(S3_BUCKET)·담당범위 정밀화. glossary(18건): ReportStatus NOT_SELECTED 추가, 매칭을 제안 수락/거절 모델로 재작성, report_issues_reviews·specialties[]·토큰 30분/14일·마이그레이션 V22/V23 정정, notification·report_holds·adjuster_reviews(report_id) 보강, PAYMENTS 미구현 표기. agents(14건): security-developer 유령 클래스(JwtAuthenticationFilter·OAuth2SuccessHandler) 정정·수동 REST OAuth 반영, backend-developer/qa 구독·결제 미구현 표기, ChatService.createRoom 유령 참조 제거, notification·outbox 담당 귀속. skills(20건): websocket-impl 전면 재작성(쿼리토큰→쿠키 핸드셰이크, @MessageMapping→REST+Redis, /ws→/ws-chat, 읽음커서·jsonb첨부), spring-security frontmatter 롤 정정·헤더우선, spring-infra actuator 인가 사실정정·Kafka 4.3.1. harness.md: infra-developer 역할표·workspace·Kafka 반영. settings.json: skills glob `*`→`**` 버그 수정 + references/harness/CLAUDE 편집 권한. springboot-dev: 전 에이전트 호출 `model`을 opus로 통일(메타원칙 "전 에이전트 opus" 정합, 기존 sonnet 6곳 정정). | CLAUDE.md, .claude/harness.md·settings.json·references/domain-glossary.md, agents/6개, skills/{springboot-dev,websocket-impl,spring-security-impl,spring-infra,spring-qa} | develop 기준 하네스 점검 요청 — 문서·에이전트·스킬이 병합된 코드(V1~V26, 쿠키인증·아웃박스·채팅·notification)와 drift → 코드 진실 기준 동기화 + model opus 통일 |
| 2026-07-27 | **커밋·PR AI 흔적 제거** — 기본 하네스가 붙이는 `Co-Authored-By: Claude`(+`noreply@anthropic.com`) 커밋 트레일러와 `🤖 Generated with Claude Code` PR 푸터를 금지하도록 오버라이드. CLAUDE.md Git Conventions에 규칙 명문화, git-workflow 스킬에 공통 0(AI 흔적 금지)·공통 1(humanize 대상에 커밋 메시지 추가)·공통 2(코드 블럭·diff 덤프 지양, 인라인 파일:라인 인용) 신설, PR 본문 양식을 프론트 참고 포맷(🔗 관련 이슈/✅ 작업 내용/🧪 테스트/💬 특이사항/🔜 후속 이슈)으로 교체(고정 체크리스트 제거), 강제 훅 `strip-ai-tells.js`(commit·gh pr/issue create·edit에서 Claude/Anthropic 표식 차단) 추가·settings.json 등록 | CLAUDE.md, .claude/skills/git-workflow, .claude/hooks/strip-ai-tells.js, .claude/settings.json, .claude/harness.md | 프론트 commit-style 스킬 참고 — 커밋·PR이 AI 생성물처럼 보이지 않게 해달라는 요청 |
| 2026-07-27 | **하네스 드리프트 동기화** — 현재 코드 대비 harness 정합 점검. CLAUDE.md 도메인 목록에 실제 구현된 `upload`(S3 presigned 파사드) 컨텍스트 반영. git-workflow scope 표: `match`를 placeholder(로직은 report/proposal)로·`payment`를 계획/미구현으로 표기, 실제 구현 컨텍스트 `notification`·`upload` 행 추가, user/adjuster/report 설명을 최근 기능(보험 정보·공개 목록·제안 수락/거절)에 맞춰 보정, 미구현 코드 참조 예시(`test(payment)`·`fix(match)`)를 실재 예시로 교체. 이슈 생성 `--assignee "이동형"`(유효하지 않은 GitHub 로그인)을 `@me`로 정정 | CLAUDE.md, .claude/skills/git-workflow | "현재 코드 보고 harness 수정할 부분 있으면 수정" 요청 — develop 기준 드리프트 감사 |
| 2026-08-06 | **OpenAPI(@Schema) required/nullable 컨벤션 신설·전체 DTO 적용** — springdoc-openapi 3.0.3(OpenAPI 3.1 생성)이 있었는데도 어떤 DTO에도 `@Schema`가 없어 required/nullable 정보가 전혀 문서화되지 않던 문제 해결. CLAUDE.md Constraints에 `requiredMode`/`nullable` 사용 규칙과 swagger-core의 중첩 객체(`$ref`) nullable 렌더링 한계(`anyOf`로도 우회 불가)를 명문화. 7개 도메인(auth·user·adjuster·report·chat·notification·upload) 58개 DTO에 컨트롤러·서비스·엔티티 코드를 근거로 적용(추측 아님) — 4개 파일(전 필드 primitive)·2개 파일(이미 Bean Validation)은 의도적으로 미변경. `/v3/api-docs` 실기동 검증으로 확인 | CLAUDE.md, domain/{auth,user,adjuster,report,chat,notification,upload}/dto/* | 사용자의 "OpenAPI json에 nullable/required 표기 가능한가" 질문에서 출발 — 컨벤션 부재 확인 후 이슈 #197로 소급 적용 결정 |
| 2026-08-09 | **OCR 트리거 브로커 self-hosted Kafka → MSK Provisioned(IAM 인증) 이전** — producer `KafkaProducerConfig`가 `security.protocol=SASL_SSL`일 때만 `AWS_MSK_IAM` 배선(PLAINTEXT는 no-op), `build.gradle`에 `aws-msk-iam-auth` 추가, `.env.example`·prod compose(`report`에 `AWS_REGION`) 반영. spring-kafka·OutboxRelay·OcrJob·아웃박스·`kafka_outbox_events`·테스트 불변(Kafka API 유지, 리네임 없음). consumer(ocr_worker, Python)는 `aws-msk-iam-sasl-signer`+OAUTHBEARER로 접속만 교체(별도 처리). spring-infra §4 stale 서술("이 클래스 불변"·"운영 MSK 추후"·consumer FastAPI) 정정 | build.gradle, infra/kafka/KafkaProducerConfig(+Test), .env.example, deploy/docker-compose.prod.yml, skills/spring-infra | 비용·이식성·성능·k8s 비교 끝에 Kafka 유지 위해 MSK 선택(PR #205) |
| 2026-08-09 | **OCR 트리거 브로커 MSK(Kafka) → AWS SQS 전환** — `spring-kafka`·`aws-msk-iam-auth` 제거 후 `awssdk:sqs` 도입, `KafkaProducerConfig` 삭제·신규 `infra/sqs/SqsConfig`(SqsClient; S3Config 패턴 = region + DefaultCredentialsProvider, 로컬은 `aws.sqs.endpoint` LocalStack override + 더미 크리덴셜, apiCallTimeout). `OutboxRelay`가 `KafkaTemplate`→`SqsClient.sendMessage`(아웃박스 `topic`=SQS 큐 이름 → GetQueueUrl 캐시), `app.outbox.enabled` 런타임 게이트. 아웃박스 테이블/엔티티(`kafka_outbox_events`/`KafkaOutboxEvent`)·`topic`/`message_key` 컬럼·OCR 계약(`OcrJob` JSON) 불변(전송 계층만 교체, 리네임·마이그레이션 없음). 설정(`spring.kafka.*` 제거)·`.env.example`·로컬 compose(kafka 컨테이너→LocalStack sqs + 큐 생성 init)·dev/prod compose(kafka·kafka-ui 제거, app·report 워커 env→SQS)·배포 env 예시 반영. 큐 타입 Standard(소비자 멱등·순서 무관). ⚠️ 운영 컷오버는 SQS 큐+DLQ 프로비저닝·IAM(app=`SendMessage`+`GetQueueUrl`, 워커=`Receive`/`Delete`)·**AI report 워커의 SQS 소비자 전환과 동시 배포**가 전제(코드 외부). | build.gradle, infra/sqs/*, application{,-test,-local}.yml, .env.example, docker-compose.yml, deploy/{docker-compose.dev,docker-compose.prod}.yml·.env.{dev,prod}.example·localstack/init-sqs.sh, skills/spring-infra, deploy/README.md, CLAUDE.md | 비용·단순성(관리형·상시 브로커 불필요) 위해 SQS 선택 — MSK 결정 번복(이슈 #208, 브랜치 fix/208-msk-to-sqs) |
| 2026-08-12 | **PII 컬럼 암호화(user_claims·user_insurances) 도입 + Flyway `core` 스키마 신설** — AES-256-GCM 봉투암호화(`PiiCipher`/`PiiAad`/`PiiEnvelope`, dev/local/test는 raw 키, prod는 KMS)로 `user_claims.additional_information`·`user_insurances`(insurer_name/product_name/policy_no/enrolled_at/coverages) 암호화(이슈 #221). 배포 중 `app_owner`가 `public` 스키마 `CREATE` 권한이 없어(PG15+ 기본 REVOKE) V33이 실패하는 사고 발생 → `core` 스키마(권한 있음)로 재이관(V35) + `flyway.schemas: core, public`/`default-schema: public` 영구 반영, **신규 마이그레이션은 `CREATE TABLE core.테이블명`으로 스키마 명시 필수** 규칙을 Key Configuration에 명문화(이슈 #223). `reports`/`report_issues`(writer=report_worker) 암호화는 report_worker QA 미완료로 보류. report_worker 연동 가이드를 `docs/pii-encryption-report-worker-handoff.md`로 신설(봉투 포맷·AAD·DEK 획득·dev·prod 키 경로 차이·CMK 분리 원칙). | CLAUDE.md, docs/pii-encryption-report-worker-handoff.md, global/security/crypto/*, infra/kms/*, domain/report/entity/UserClaim.java, domain/user/entity/UserInsurance.java, db/migration/V33~V35 | PII 컬럼 저장 단계 암호화 요청(이슈 #221) — 진행 중 발견한 스키마 권한 사고(#223) 재발 방지까지 하네스에 반영 |
| 2026-08-13 | **OCR 아웃박스 클래스 `Kafka*` → `Ocr*` 리네임(테이블은 보류)** — 브로커가 SQS로 바뀌었는데 클래스에 옛 브로커 이름이 남아 stale하던 것을 정리. `KafkaOutboxEvent`/`KafkaOutboxStatus`/`KafkaOutboxRepository`→`OcrOutbox*`, 참조 4곳(`OutboxRelay`·`OcrJobOutboxPort(Impl)`·`OutboxRelayTest`)·stale 주석("Kafka로 발행"·"Kafka 파티션 키") 동반 정리. **테이블 `kafka_outbox_events` 리네임은 되돌렸다** — 처음엔 V40으로 `ocr_outbox_events` 리네임을 넣었으나 스키마 배치가 드리프트된 상태라 정리 전까지 손대지 않기로 했다. (경위: `RENAME TO`가 대상 스키마 `CREATE` 권한을 요구한다는 걸 실측하고 `public` 권한이 없는 dev에서 실패한다고 판단했는데, 실제로는 **그 테이블이 dev·prod 모두 `core`에 있고 거기엔 권한이 있어 통과했을 것**이다 — 테이블 위치를 확인하지 않은 오판이었다.) 이 과정에서 **더 큰 문제를 발견**했다: 앱 테이블이 전부 `core`에 있는데 마이그레이션은 `public`에 만든다 — 다음 신규 테이블 마이그레이션이 배포 시 앱 부팅을 깨뜨린다(이슈로 분리). Key Configuration의 스키마 규칙에 권한 매트릭스(이름 해석≠권한, `RENAME`·`ADD CONSTRAINT UNIQUE`도 `CREATE` 권한 필요)와 **"권한보다 앱이 못 찾는 게 더 큰 이유"** 근거를 반영. CLAUDE.md의 두 아웃박스 혼동 서술도 정정(SQS 릴레이는 `OutboxProcessor`가 아니라 `OutboxRelay`, `outbox_events`=회원 탈퇴 후처리 / `kafka_outbox_events`=OCR 발행). | CLAUDE.md, infra/outbox/OcrOutbox*, infra/sqs/* | 브로커명이 박혀 stale해진 이름 정리 요청 — 클래스만 정리하고 테이블은 권한 정리 후 별도 처리 |
| 2026-08-13 | **스키마 배치를 `core`로 통일 + 롤 `search_path` 의존 제거** — dev·prod의 앱 테이블은 이미 전부 `core`에 있는데(#223 수습 때 이관) 레포 마이그레이션은 `public`에 만들어, 빈 DB로 세우면 운영과 다른 배치가 되고 앱이 기동하지 못하는 상태였다(로컬 3회 재현). `V40__move_public_tables_to_core.sql`이 `public` 잔여 테이블·시퀀스를 `core`로 이관해 간극을 메운다(기존 환경 no-op·멱등, `flyway_schema_history` 제외, `SET SCHEMA`라 `public` 권한 불필요). datasource URL에 `?currentSchema=core,public`을 명시해 Hibernate 기본 스키마와 원시 JDBC(`EncryptionKeyStore`) 해석을 레포 안에서 고정 — DB 롤 설정이 사라져도 앱이 동작한다. 검증: 신규 환경·prod 릴리즈 리허설(V32+`core`→V33~V40)·dev 조건(`public` 권한 없음) 3종 기동 확인. 후속: prod `public` CREATE 회수로 dev와 권한 일치(#245). | CLAUDE.md, application.yml, db/migration/V40 | 스키마 드리프트 근본 정리(#244) — 전부 `core`로 통일 결정 |
| 2026-08-15 | **리포트 분석 처리 상태 노출 기능(OCR 실패·AI 가드레일 차단) + 하네스 동기화.** AI 워커가 `ai.ocr_job_failures`(OCR 실패 저널, AI 소유·Backend SELECT 전용)에 기록한 확정 실패와 `reports.status='BLOCKED'`(입력 가드레일 차단, AI 워커 원시 SQL 세팅) 둘 다 무음 실패였던 걸 `GET /reports/{reportId}/analysis-status` + 목록/상세 평면 3필드로 노출하고, 각각 별도 스케줄러 스윕(`AnalysisFailureNotificationSweeper`/`BlockedReportNotificationSweeper`)으로 인앱 알림·FCM 푸시까지 보낸다(V41·V42, `reports` 컬럼 추가만). `ReportStatus`에 `BLOCKED`(종료 상태) 추가. 부수적으로 **springdoc-openapi가 Spring의 Jackson 3(`SNAKE_CASE`) 설정을 못 보고 자체 정적 싱글턴(`Json31.mapper()`)으로 스키마를 만들어 `/v3/api-docs` 프로퍼티명이 전부 camelCase였던 저장소 전역 드리프트를 발견·정정**(602개 중 599개, Spring Boot 4 주의 섹션에 반영). `CreateReportResponse.status`가 `customerStatus()` 매핑을 우회하던 기존 불일치도 정정. 하네스 동기화: domain-glossary §3·§3-1(신설)·§10·§13·§14·§17, qa-reviewer 도메인 enum 체크리스트에 `BLOCKED`/`AnalysisState` 추가, spring-infra HikariCP 값(10→15)과 `REQUIRES_NEW` 이중 커넥션 점유 근거 반영. | CLAUDE.md, .claude/references/domain-glossary.md, .claude/agents/qa-reviewer.md, .claude/skills/spring-infra/SKILL.md, .claude/skills/ddd-tactical/SKILL.md, domain/report/**, domain/notification/**, global/config/OpenApiConfig.java | PR #247·#248 구현 후 "하네스 보고 코드와 다른 점·새로 강제할 점·새 스킬 필요한 점 수정해달라" 요청 |
| 2026-08-16 | **OCR 품질 미달 상태(`NEEDS_REUPLOAD`) 도입 — BLOCKED와 동일 패턴.** AI 워커가 OCR 신뢰도 미달 + 이름/도메인 정보 미검출(흐릿한 사진 등)로 리포트 생성을 건너뛸 때 `reports.status`를 원시 SQL로 직접 `'NEEDS_REUPLOAD'`로 세팅하지만 Backend가 그 신호를 전혀 소비하지 않아 리포트가 이전 상태에 무기한 멈추는 동일한 무음 정지를 해소. **핵심 발견: `reports.status`엔 DB CHECK 제약도 PostgreSQL enum도 없다**(`varchar(30)`, 값 목록은 Java enum `ReportStatus`에서만 강제) — 즉 이 값 추가 자체엔 마이그레이션이 필요 없지만, **Backend 배포가 그 값을 쓰는 AI 워커보다 반드시 선행해야 한다**(역순이면 Hibernate `Enum.valueOf` 실패로 리포트 목록·상세 전체가 500). `ReportStatus`·`AnalysisState`(5번째 값, `BLOCKED` 다음·AI 초안 검사보다 앞 배치 — 저널 기반 실패 스윕과 중복 알림을 구조적으로 차단) 확장, 알림 멱등 가드 컬럼 `reports.needs_reupload_notified_at` 추가(V43, 값 자체가 아니라 가드용), 신규 스윕러 `NeedsReuploadNotificationSweeper`, 신규 알림 타입 `NotificationType.REPORT_NEEDS_REUPLOAD`(형제 값 `ANALYSIS_FAILED`·`REPORT_BLOCKED`와의 네이밍 일관성 때문에 ai_owner 제안 원문 `NEEDS_REUPLOAD`에서 정정 — QA WARNING을 배포 전에 반영). 문서 단위 상세(`ai.ocr_results` GRANT 연동)는 `core.ocr_results`라는 동명이표 테이블이 이미 존재하는 함정 때문에 이번 스코프에서 제외하고 후속 이슈로 분리(`failed_documents`는 당분간 빈 배열). 하네스 동기화: domain-glossary §3·§3-1·§13·§14·§17, qa-reviewer 도메인 enum 체크리스트에 `NEEDS_REUPLOAD` 반영. | CLAUDE.md, .claude/references/domain-glossary.md, .claude/agents/qa-reviewer.md, domain/report/**, domain/notification/**, db/migration/V43 | ai_owner(OCR 워커)가 OCR 품질 미달 무음 정지를 신고, BLOCKED(PR #247·#248) 때와 동일 해법 요청 |
| 2026-08-16 | **NEEDS_REUPLOAD PR(#251) CodeRabbit 리뷰·크로스팀 조율 중 얻은 교훈 하네스 반영.** ① `ddd-tactical` §5에 REQUIRES_NEW 격리 함정 신설 — 자가 호출(self-invocation)은 `@Transactional` 프록시를 안 타 새 트랜잭션이 안 열리고(별도 Bean 필요, `TerminalFailureJournalReader` 신설이 실제 사례), REQUIRES_NEW 메서드 안에서 예외를 삼키면 PostgreSQL이 이미 aborted시킨 세션을 커밋하려다 다시 실패한다(예외는 메서드 밖으로 던져 호출자가 잡아야 함) — `ReportAnalysisStatusQueryService.resolveAll` degrade 버그를 고치며 두 번 실패하고 알아낸 내용. ② CLAUDE.md Key Configuration에 Flyway 버전 충돌 규칙 추가 — 마이그레이션 번호는 `develop` HEAD뿐 아니라 그 시점 열려 있는 다른 PR과도 충돌할 수 있다(PR #251·#249가 둘 다 `V43` 사용), 나중에 머지되는 쪽이 재번호. ③ domain-glossary NOTIFICATIONS에 `NotificationType` 네이밍 규칙(도메인 접두어, 예 `REPORT_`) 명문화 + qa-reviewer 체크리스트에 검증 항목 추가 — ai_owner가 제안한 `NEEDS_REUPLOAD`를 형제 값과 맞춰 `REPORT_NEEDS_REUPLOAD`로 배포 전 정정한 사례가 근거. ④ spring-infra §3에 현재 배포가 단일 인스턴스(replica 없음)라는 사실 반영 — 스윕러들이 `@Version`·행 잠금 없이 동시 실행 위험을 감수하는 근거이며, CodeRabbit이 `NeedsReuploadNotificationSweeper`에 지적한 동시성 이슈를 이 근거로 이번 PR 범위에서는 반영하지 않기로 판단. | .claude/skills/ddd-tactical/SKILL.md, CLAUDE.md, .claude/references/domain-glossary.md, .claude/agents/qa-reviewer.md, .claude/skills/spring-infra/SKILL.md | PR #251 CodeRabbit 리뷰 반영 + ai_owner와의 배포 순서·GRANT 조율 과정에서 나온 재사용 가능한 교훈을 하네스에 적용해달라는 요청 |
