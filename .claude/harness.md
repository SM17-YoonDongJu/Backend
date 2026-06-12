# AI Harness Design Principles

## Purpose

이 하네스는 Spring Boot 백엔드 개발 과정을 분석, 설계, 구현, 검증, 형상관리 단계로 나누고 각 단계에 맞는 전문 에이전트를 배치한 SDLC 기반 개발 자동화 구조다.
https://github.com/revfactory/harness 코드를 기반으로하여 우리 프로젝트에 맞게 수정하였다.

핵심 목표는 다음과 같다.

- 현재 코드베이스와 도메인 제약을 먼저 이해한 뒤 구현한다.
- 보안, 실시간 통신, 비즈니스 로직, QA 역할을 분리한다.
- AI 산출물을 바로 신뢰하지 않고 독립 QA 단계에서 재검증한다.
- 구현 자동화는 허용하되 commit/PR 같은 외부 반영은 사용자 승인 뒤에만 수행한다.
- 법적 리스크가 있는 손해사정 도메인은 glossary와 QA 규칙으로 임의 해석을 제한한다.

---

## 1. SDLC-Oriented Workflow

전체 흐름은 실제 백엔드 개발 사이클을 따른다.

```text
request.md
  -> backend-analyst
  -> design.md
  -> backend/security/realtime developer
  -> summary.md
  -> qa-reviewer
  -> review-report.md
  -> user approval
  -> git-workflow
```

각 단계의 책임은 명확히 분리한다.

- 분석 단계: 코드베이스 탐색, 요구사항 해석, API 계약, DB 스키마, 경계 케이스 정의
- 구현 단계: 역할별 전문 에이전트가 소스 코드와 마이그레이션 작성
- QA 단계: 보안, 트랜잭션, N+1, 멱등성, RBAC, 컴플라이언스 검증
- Git 단계: 사용자 승인 후 commit/PR/CodeRabbit 연동

이 구조는 LLM이 요구사항을 바로 코드로 변환하면서 생기는 hallucination과 설계 누락을 줄이기 위한 것이다.

---

## 2. Analysis Before Implementation

모든 기능 구현은 `backend-analyst`가 먼저 실행되는 것을 원칙으로 한다.

분석 에이전트는 다음을 수행한다.

- 현재 패키지 구조와 기존 코드 패턴 확인
- API 요청/응답 계약 정의
- DB 변경 사항과 Flyway 마이그레이션 계획 수립
- 역할별 권한 요구사항 명시
- 구현 에이전트별 작업 범위 분리
- QA가 검증할 경계 케이스 작성

구현 에이전트는 `_workspace/01_analyst/design.md`를 계약 문서로 보고 작업한다.
즉, 구현은 즉흥적인 코드 생성이 아니라 분석 산출물에 대한 실행 단계다.

---

## 3. Role-Based Multi-Agent Design

하네스는 역할별 전문성을 기준으로 에이전트를 나눈다.

| Agent | Responsibility |
| --- | --- |
| `backend-analyst` | 코드 탐색, 요구사항 분석, API/DB 설계 |
| `backend-developer` | Controller, Service, Repository, 비즈니스 로직 |
| `security-developer` | Spring Security, JWT, OAuth2, RBAC, Redis Refresh Token |
| `realtime-developer` | WebSocket/STOMP, 채팅, presence, FCM 오프라인 푸시 |
| `qa-reviewer` | 코드 리뷰, 테스트, 보안/컴플라이언스 검증 |

역할을 분리한 이유는 다음과 같다.

- 보안과 비즈니스 로직의 책임을 분리해 권한 누락을 줄인다.
- WebSocket, FCM, Redis presence 같은 실시간 통신 관심사를 별도 전문 영역으로 둔다.
- QA를 구현자와 분리해 독립 검증 관점을 유지한다.
- 복합 기능에서는 필요한 에이전트만 선택해 병렬 구현할 수 있다.

---

## 4. Domain Glossary As Source Of Truth

손해사정 도메인은 법적, 정책적 제약이 강하다.
따라서 도메인 설계 시 `.claude/references/domain-glossary.md`를 먼저 읽고, glossary에 없는 내용은 임의로 확정하지 않는다.

glossary는 다음 기준을 제공한다.

- 사용자 역할 enum
- 리포트 상태 머신
- 사정사 검수 및 사용자 평가 모델
- 구독 플랜 값
- 매칭 플로우
- 보험업법, 개인정보보호법, 금소법 관련 구현 제약

불명확한 항목은 design.md의 가정 목록에 기록하고, 리더 또는 사용자 확인 대상으로 남긴다.

---

## 5. Quality Gates

AI 구현 결과는 반드시 QA 단계를 거친다.

`qa-reviewer`는 다음 항목을 중점 검증한다.

- 인증/인가 누락
- 권한 상승 가능성
- 민감정보 노출
- 트랜잭션 경계 누락
- JPA N+1 가능성
- 결제 웹훅 멱등성
- FCM 실패가 비즈니스 트랜잭션을 롤백시키는지 여부
- RBAC 401/403 동작
- Flyway DDL과 JPA 엔티티 정합성
- 도메인 enum 일관성

이슈는 `CRITICAL`, `WARNING`, `INFO`로 분류한다.
`CRITICAL` 이슈가 있으면 해당 구현 에이전트를 부분 재실행하는 구조를 사용한다.

---

## 6. Compliance-Aware Review

이 서비스는 손해사정 연결 도메인이므로 일반적인 코드 품질 외에 컴플라이언스 검증이 필요하다.

QA 단계에서 CRITICAL로 보는 항목은 다음과 같다.

- 사정사 서명 정보 누락
- 보상금액 단정 표현
- 법률자문으로 해석될 수 있는 응답 문구
- 타 사정사의 검수 내용 노출
- glossary와 다른 enum 값 사용

이 검증은 비즈니스 취향 문제가 아니라 법적 리스크 방어 장치다.

---

## 7. Controlled Automation

하네스는 구현 자동화를 지원하지만 모든 작업을 무조건 자동 실행하지 않는다.

허용하는 자동화:

- `_workspace/**` 산출물 작성
- `src/**` 소스 코드 작성
- `build.gradle`, `settings.gradle` 수정
- `./gradlew compileJava`
- `./gradlew test`
- `git status`, `git diff`

사용자 승인이 필요한 작업:

- commit
- push
- PR 생성
- CodeRabbit 리뷰 트리거
- 외부 서비스에 영향을 주는 GitHub 작업

이 경계는 human-in-the-loop 원칙을 위한 것이다.
AI는 구현과 검증을 자동화하지만, 저장소 히스토리와 외부 협업 표면에 반영하는 결정은 사용자가 통제한다.

---

## 8. Workspace As Execution Memory

`_workspace`는 에이전트 간 상태 공유와 작업 추적을 위한 실행 메모리다.

주요 파일:

- `_workspace/00_input/request.md`: 사용자 요청과 현재 작업 입력
- `_workspace/01_analyst/design.md`: 분석 결과와 구현 계약
- `_workspace/02_backend/summary.md`: 비즈니스 구현 요약
- `_workspace/02_security/summary.md`: 보안 구현 요약
- `_workspace/02_realtime/summary.md`: 실시간 기능 구현 요약
- `_workspace/03_qa/review-report.md`: QA 결과

중요한 원칙:

- `_workspace`는 삭제하지 않고 보존한다.
- 새 기능 작업 시 기존 workspace가 있으면 백업하거나 현재 작업 기준으로 갱신한다.
- workspace 내용은 실제 워크트리와 일치해야 한다.
- stale summary를 근거로 QA하거나 후속 구현하지 않는다.

---

## 9. Repository-Aware Implementation

하네스는 현재 Spring Boot 환경을 기준으로 구현한다.

현재 기준:

- Spring Boot 4.0.6
- Java 21
- Gradle
- PostgreSQL + Flyway
- `ddl-auto=validate`
- Redis
- Firebase Admin SDK
- S3 SDK
- Jackson snake_case
- `open-in-view=false`

구현 원칙:

- JPA 엔티티를 추가하면 반드시 Flyway migration도 추가한다.
- 조회 서비스는 기본적으로 `@Transactional(readOnly = true)`를 사용한다.
- 비즈니스 예외는 `BusinessException(ErrorCode)` 패턴을 따른다.
- 응답 필드는 Jackson 설정을 이용해 snake_case로 직렬화한다.
- 보안이 필요한 엔드포인트는 SecurityConfig 또는 `@PreAuthorize`로 명시한다.

---
