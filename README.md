# 바른보상 Backend

보험금 지급 결과에 의문이 있는 사용자에게 AI 분석 리포트를 제공하고, 독립 손해사정사의 검수와 상담으로 연결하는 서비스의 Spring Boot API 서버다.

설계 배경, 장애 기록, 기술 결정의 근거는 [위키](https://github.com/SM17-YoonDongJu/Backend/wiki)에 있다. 이 문서는 코드를 처음 여는 사람이 구조를 잡고 로컬에서 띄울 수 있도록 하는 데 집중한다.

<p align="center"><img src="https://github.com/SM17-YoonDongJu/Backend/wiki/images/project-overview/system-architecture.png" width="90%" alt="시스템 구성도"></p>

## 이 서버가 맡는 일

| Spring Boot (이 레포) | AI 워커 (FastAPI, 별도 레포) |
|---|---|
| 인증·회원 (JWT, 카카오·네이버·애플 로그인, 역할 기반 인가) | OCR, 개인정보 마스킹 |
| 사고 정보·진단서 접수, S3 업로드, OCR 트리거 발행(SQS) | 하이브리드 RAG 검색, LangGraph 리포트 생성, 가드레일 |
| 손해사정사 검수·제안 수락/거절, 상담 채팅(WebSocket) | SQS 메시지 소비 이후의 처리 |
| 인앱 알림, FCM 푸시 | |

리포트 생성 요청은 이 서버가 받아 같은 트랜잭션 안에 아웃박스로 적재하고, 릴레이가 SQS로 발행한다. AI 워커가 남긴 결과와 상태(분석 실패, 가드레일 차단, 재업로드 필요)는 스케줄러가 읽어 알림으로 바꾼다.

## 기술 스택

| 분류 | 사용 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.0 (Spring Framework 7, Jackson 3) |
| 데이터 | PostgreSQL 16 + pgvector, Spring Data JPA, QueryDSL, Flyway, Redis 7 |
| 인증 | Spring Security, JWT(HttpOnly 쿠키), Refresh Token Rotation(Redis Lua) |
| 실시간 | WebSocket(STOMP) 구독 + REST 전송, Redis pub/sub |
| AWS | S3, SQS, KMS(PII 봉투 암호화), RDS |
| 문서·관측 | springdoc-openapi + Scalar, Actuator, Prometheus, Grafana, Loki, Tempo |
| 배포 | Docker, GitHub Actions, Kubernetes + Argo CD(dev), EC2 + docker compose(prod) |

## 로컬에서 실행하기

필요한 것: JDK 21, Docker

1. 환경 변수 파일을 만든다. `cp .env.example .env` 후 `DB_PASSWORD`, `JWT_SECRET`(32자 이상) 등 필수값을 채운다.
2. 인프라를 띄운다. `docker compose up -d`를 실행하면 PostgreSQL(pgvector), Redis, LocalStack(SQS)이 뜨고 OCR 큐도 자동으로 만들어진다.
3. 앱을 실행한다. `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`
4. API 문서는 `http://localhost:8080/docs`(Scalar)와 `/v3/api-docs`에서 본다. 엔드포인트와 스키마는 이 문서가 기준이다.

`local` 프로파일은 쿠키 `Secure`를 끄고, API 문서를 인증 없이 열고, 개발용 로그인 엔드포인트를 켠다. 배포 환경에서는 쓰지 않는다.

자주 쓰는 명령:

| 명령 | 용도 |
|---|---|
| `./gradlew build` | 빌드 |
| `./gradlew test` | 전체 테스트 |
| `./gradlew test --tests "com.soma.backend.domain.auth.*"` | 일부 테스트 |
| `./gradlew checkstyleMain` | 코드 스타일 검사 |
| `docker compose --profile app up -d` | 앱까지 컨테이너로 실행 |

QA용 API 테스트는 Gradle과 별개인 `qa-e2e/`(Playwright) 프로젝트에 있다.

## 프로젝트 구조

도메인(Bounded Context)으로 먼저 나누고, 각 도메인 안을 레이어로 나눈다. 비즈니스 규칙은 서비스가 아니라 엔티티 메서드에 둔다.

| 경로 | 내용 |
|---|---|
| `domain/auth` | 소셜 로그인, 토큰 발급·재발급·로그아웃 |
| `domain/user` | 회원, 마이페이지, 보험 정보, 탈퇴 |
| `domain/adjuster` | 손해사정사 신청·프로필·공개 목록, 사정사 홈 |
| `domain/report` | 리포트 접수·조회, 검수, 제안 수락/거절, 분석 상태 |
| `domain/chat` | 상담 채팅방, 메시지, 첨부, 읽음 처리 |
| `domain/notification` | 인앱 알림, FCM 푸시, 알림 설정 |
| `domain/upload` | S3 presigned 업로드 |
| `global/` | 보안(JWT 필터, 쿠키), 예외 처리, 공통 응답, 설정 |
| `infra/` | Redis, S3, SQS, KMS, FCM, 아웃박스 |

각 도메인은 `controller / dto / entity / repository / service`로 나뉜다. 의존 방향은 항상 `entity` 쪽을 향하고, `entity`는 Spring Web이나 서비스를 알지 못한다.

## 알아 두면 좋은 규칙

**응답 형식.** 성공은 `{ "status": "200", "message": "...", "data": {...} }`, 실패는 `{ "status": "400", "code": "ERROR_CODE", "message": "..." }`이고 필드명은 snake_case다. 컨트롤러는 `ApiResponse<T>`를 그대로 반환하고, 200이 아닌 성공 상태는 `@ResponseStatus`로 선언한다. 예외는 `BusinessException(ErrorCode)`로 던진다.

**인증.** 토큰은 `access_token`, `refresh_token` HttpOnly 쿠키로만 주고받는다. `Authorization: Bearer` 헤더는 지원하지 않으니, 서버 간 호출이나 부하 테스트도 쿠키를 실어야 한다.

**조회 쿼리.** native query는 쓰지 않는다. 동적 조회는 QueryDSL, 단순 조회는 Spring Data 파생 쿼리나 JPQL로 쓴다. 불가피한 경우에만 사유를 주석으로 남긴다.

**DB 마이그레이션.** 스키마는 Flyway(`src/main/resources/db/migration/V{n}__설명.sql`)로만 바꾸고 JPA는 `validate`다. 새 테이블과 인덱스는 반드시 `core.` 스키마를 붙여 만든다. 붙이지 않으면 배포 후 앱이 테이블을 찾지 못해 기동에 실패한다. 번호는 develop뿐 아니라 열려 있는 다른 PR과도 겹치지 않는지 확인한다.

**브랜치와 커밋.** `feature/*` → `develop` → `main`의 Git Flow를 쓴다. 브랜치는 `<type>/<이슈번호>-<요약>`, 커밋은 `<type>(<scope>): 한국어 설명` 형식이다. 버전 태그는 `main`에 머지될 때 semantic-release가 커밋 타입을 보고 붙인다.

## 배포 환경

| 환경 | 방식 |
|---|---|
| local | `docker compose`(DB·Redis·LocalStack) + `bootRun` |
| dev | GitHub Actions가 이미지를 빌드해 비공개 매니페스트 레포의 태그를 갱신하면 Argo CD가 Kubernetes에 반영한다. 반영 뒤 `/actuator/info`의 커밋 해시로 배포를 확인한다 |
| prod | GitHub Actions가 SSM으로 EC2에 접속해 docker compose로 교체한다 |

운영 절차와 환경 변수는 [`deploy/README.md`](deploy/README.md), 클러스터 구성과 런북은 위키의 [Kubernetes](https://github.com/SM17-YoonDongJu/Backend/wiki/Kubernetes) 페이지에 있다.

## 더 보기

- [위키 Home](https://github.com/SM17-YoonDongJu/Backend/wiki): 사례 기록 목록
- [프로젝트 개요](https://github.com/SM17-YoonDongJu/Backend/wiki/Project-Overview): 서비스가 왜 이런 모양인지
- [결정 로그](https://github.com/SM17-YoonDongJu/Backend/wiki/Decision-Log): 주요 기술 결정과 그 근거
- [용어집](https://github.com/SM17-YoonDongJu/Backend/wiki/Glossary): 도메인 용어와 코드 이름
