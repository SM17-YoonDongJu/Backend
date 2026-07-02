---
name: ddd-tactical
description: "Spring Boot 전술적 DDD(Tactical DDD) 구현 가이드. Bounded Context별 domain·application·presentation·infrastructure 4계층 구조, Aggregate·Entity·Value Object·Domain Service·도메인 이벤트 모델링, Repository 포트/어댑터, Application Service 트랜잭션 경계, 레이어 간 DTO 매핑, 안티패턴, 레이어드→DDD 점진 마이그레이션을 다룬다. 새 도메인/엔티티/유스케이스 설계·구현, 패키지 구조 결정, '애그리거트', 'VO', '도메인 모델', 'DDD 구조' 관련 작업 시 반드시 이 스킬을 참조. backend-analyst(설계)·backend-developer(구현)가 공유."
---

# Spring Boot 전술적 DDD 구현 가이드

이 프로젝트는 **전술적 DDD**를 지향한다. 핵심은 "비즈니스 규칙(불변식)을 도메인 모델 안에 두고, 프레임워크·인프라를 바깥으로 밀어내는 것"이다. backend-analyst는 이 가이드로 Aggregate 경계와 유스케이스를 설계하고, backend-developer는 이 구조로 구현한다.

## 목차
1. 패키지 구조 (Bounded Context × 4계층)
2. 레이어 의존 규칙
3. 전술 패턴: Aggregate / Entity / VO
4. Repository (포트/어댑터)
5. Application Service (트랜잭션 경계)
6. Presentation (컨트롤러·DTO 매핑)
7. 도메인 이벤트
8. 안티패턴
9. 레이어드 → DDD 마이그레이션
10. 프로젝트 제약과의 정합

## 1. 패키지 구조

컨텍스트(도메인)를 최상위로, 그 안을 4계층으로 나눈다.

```
com.soma.backend.match
├── domain/
│   ├── model/         MatchRequest(Aggregate Root), MatchStatus(VO/enum)
│   ├── repository/    MatchRequestRepository (인터페이스=포트)
│   ├── service/       MatchingPolicy (여러 Aggregate 규칙)
│   └── event/         MatchAcceptedEvent
├── application/
│   ├── MatchCommandService.java   유스케이스 (트랜잭션 경계)
│   └── dto/           RequestMatchCommand, MatchResult
├── presentation/
│   ├── controller/    MatchController
│   └── dto/           MatchRequestRequest, MatchResponse
└── infrastructure/
    └── persistence/   MatchRequestJpaRepository (Spring Data), MatchRequestRepositoryImpl
```

- 한 컨텍스트 = 한 최상위 패키지. 컨텍스트 간 직접 참조는 피하고, 꼭 필요하면 application 레이어에서 조합하거나 도메인 이벤트로 연결한다.
- `global/`(config·exception·security), `infra/`(redis·s3·fcm·kafka)는 전역 공유로 유지한다.

## 2. 레이어 의존 규칙

의존은 **항상 안쪽(domain)으로**. 안은 바깥을 모른다.

| 레이어 | 의존 가능 | 금지 |
|--------|-----------|------|
| domain | (JPA 애노테이션까지만) | Spring Web, application, presentation, infrastructure |
| application | domain | presentation, 구체 infrastructure 클래스(포트 인터페이스로만) |
| presentation | application, (조회용) domain | infrastructure 직접 참조 |
| infrastructure | domain(포트 구현) | presentation |

컴파일러가 강제하진 않으므로 리뷰(qa-reviewer)와 import 점검으로 지킨다. import에 `presentation`이 `infrastructure`를 참조하거나 `domain`이 `org.springframework.web`을 참조하면 위반이다.

## 3. Aggregate / Entity / Value Object

**실용 모드 (기본):** JPA 엔티티가 곧 Aggregate Root다. 애노테이션을 도메인 모델에 허용하되, **비즈니스 로직을 엔티티 안에** 둔다(setter 남발 금지). 순수 분리(도메인 POJO + 별도 JPA 엔티티 + 매퍼)는 불변식이 복잡한 소수 Aggregate에만 선택 적용한다.

```java
// domain/model/MatchRequest.java — Aggregate Root
@Entity
@Table(name = "match_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchRequest {

    @Id
    private UUID id;                       // PK는 UUID (프로젝트 규약)

    @Column(nullable = false)
    private UUID userId;                   // 다른 Aggregate(User)는 ID로만 참조

    @Column(nullable = false)
    private UUID adjusterId;

    @Enumerated(EnumType.STRING)
    private MatchStatus status;            // VO(enum)

    // 정적 팩터리 — 생성 규칙을 한곳에
    public static MatchRequest create(UUID userId, UUID adjusterId) {
        MatchRequest request = new MatchRequest();
        request.id = UUID.randomUUID();
        request.userId = userId;
        request.adjusterId = adjusterId;
        request.status = MatchStatus.CONNECTED;   // 즉시 연결 (수락 단계 없음)
        return request;
    }

    // 비즈니스 규칙은 엔티티 안에서 상태를 바꾼다 (불변식 보호)
    public void cancel() {
        if (this.status == MatchStatus.CANCELED) {
            throw new BusinessException(ErrorCode.MATCH_ALREADY_CANCELED);
        }
        this.status = MatchStatus.CANCELED;
    }
}
```

**Value Object** — 식별자 없는 불변 값은 `record`로. 동등성은 값으로 판단된다.

```java
// domain/model/Money.java
public record Money(long amount, String currency) {
    public Money {
        if (amount < 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
    }
    public Money add(Money other) {
        return new Money(this.amount + other.amount, this.currency);
    }
}
```

- Aggregate는 **불변식 경계**다. 외부는 Root 메서드를 통해서만 내부를 바꾼다.
- Aggregate 간 참조는 **객체가 아니라 ID(UUID)**로. `@ManyToOne`으로 다른 Aggregate를 물지 않는다(한 Aggregate가 비대해지고 경계가 무너진다).
- Domain Service는 "한 엔티티에 넣기 애매한, 여러 Aggregate에 걸친 규칙"에만 쓴다. 대부분의 규칙은 엔티티 메서드로 충분하다.

## 4. Repository (포트/어댑터)

인터페이스는 도메인에, 구현은 인프라에.

```java
// domain/repository/MatchRequestRepository.java  (포트)
public interface MatchRequestRepository {
    MatchRequest save(MatchRequest request);
    Optional<MatchRequest> findById(UUID id);
}

// infrastructure/persistence/MatchRequestJpaRepository.java  (Spring Data)
interface MatchRequestJpaRepository extends JpaRepository<MatchRequest, UUID> { }

// infrastructure/persistence/MatchRequestRepositoryImpl.java  (어댑터)
@Repository
@RequiredArgsConstructor
public class MatchRequestRepositoryImpl implements MatchRequestRepository {
    private final MatchRequestJpaRepository jpa;

    @Override
    public MatchRequest save(MatchRequest request) {
        return jpa.save(request);
    }

    @Override
    public Optional<MatchRequest> findById(UUID id) {
        return jpa.findById(id);
    }
}
```

- Repository는 **Aggregate 단위로만** 저장/조회한다. 내부 Entity를 따로 조회하지 않는다.
- 복잡한 조회(목록·검색·통계)는 CQRS로 분리 가능: 조회 전용 QueryService가 `presentation/dto` 또는 별도 read model을 직접 반환. 쓰기 경로만 Aggregate/Repository를 거친다.

## 5. Application Service (트랜잭션 경계)

유스케이스 한 개 = 메서드 한 개. **`@Transactional`은 여기에만** 둔다(`open-in-view: false`라 응답 전에 트랜잭션이 끝나야 한다).

```java
// application/MatchCommandService.java
@Service
@RequiredArgsConstructor
public class MatchCommandService {

    private final MatchRequestRepository matchRequestRepository;
    private final ChatRoomPort chatRoomPort;              // 다른 컨텍스트는 포트로
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public MatchResult requestMatch(RequestMatchCommand command) {
        MatchRequest request = MatchRequest.create(command.userId(), command.adjusterId());
        matchRequestRepository.save(request);
        chatRoomPort.createRoom(command.userId(), command.adjusterId());   // 즉시 연결
        eventPublisher.publishEvent(new MatchAcceptedEvent(request.getId()));
        return MatchResult.from(request);
    }
}
```

- 입력은 `Command`(presentation DTO가 아니라 application DTO). 출력은 `Result`.
- 조회 메서드는 `@Transactional(readOnly = true)`.
- 컨텍스트 간 협력은 구체 클래스가 아니라 **포트 인터페이스**(`ChatRoomPort`)로 받아 결합도를 낮춘다.

## 6. Presentation (컨트롤러·DTO 매핑)

컨트롤러는 얇게. HTTP ↔ 유스케이스 변환만 한다.

```java
// presentation/controller/MatchController.java
@RestController
@RequestMapping("/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchCommandService matchCommandService;

    @PostMapping
    public ResponseEntity<ApiResponse<MatchResponse>> requestMatch(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody MatchRequestRequest body) {

        RequestMatchCommand command = body.toCommand(principal.getUserId());
        MatchResult result = matchCommandService.requestMatch(command);
        return ResponseEntity.ok(ApiResponse.ok(MatchResponse.from(result)));
    }
}
```

- Request/Response DTO는 `presentation/dto`. 필드는 snake_case(Jackson 전역), 검증은 `@Valid`.
- **도메인 모델·JPA 엔티티를 컨트롤러 밖으로 노출하지 않는다.** 매핑은 DTO의 `toCommand()`/`from()` 정적 메서드에 둔다.
- 매핑 방향: Request → Command → (domain) → Result → Response.

## 7. 도메인 이벤트

여러 Aggregate/컨텍스트를 한 트랜잭션에 묶지 않기 위해 이벤트로 결합을 끊는다.

```java
// domain/event/MatchAcceptedEvent.java
public record MatchAcceptedEvent(UUID matchRequestId) { }

// 구독 측 (application 또는 infrastructure)
@Component
class MatchAcceptedListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(MatchAcceptedEvent event) {
        // FCM 발송 등 부수효과 — 커밋 후 실행, 메인 트랜잭션과 분리
    }
}
```

- 커밋 후 부수효과(FCM 푸시, Kafka 발행 등)는 `AFTER_COMMIT` 리스너로. 실패해도 메인 트랜잭션은 이미 커밋됨.

## 8. 안티패턴 (리뷰에서 잡을 것)

| 안티패턴 | 문제 | 교정 |
|----------|------|------|
| 빈약한 도메인(Anemic Domain) | 엔티티가 getter/setter뿐, 로직은 Service에 | 비즈니스 규칙을 엔티티 메서드로 이동 |
| Aggregate 간 `@ManyToOne` 직접 참조 | 경계 붕괴, N+1, 거대 그래프 | ID(UUID) 참조로 전환 |
| `@Transactional`을 도메인/컨트롤러에 | 경계 모호, open-in-view 위반 | application service에만 |
| JPA 엔티티를 Response로 반환 | 내부 노출, 지연로딩 직렬화 오류 | presentation DTO로 매핑 |
| domain이 Spring Web import | 의존 방향 역전 | 순수 도메인 유지 |
| Repository가 내부 Entity 단위 저장 | Aggregate 불변식 우회 | Root 단위 저장 |

## 9. 레이어드 → DDD 마이그레이션

기존 `domain/*/{controller,service,dto}` 코드는 **도메인 단위로 점진 이전**한다(한 번에 전면 개편 금지).

1. 대상 컨텍스트 하나 선택 (예: match)
2. 새 4계층 패키지 생성 → 엔티티를 `domain/model`로 옮기고 로직을 엔티티 안으로
3. 기존 Service를 `application`(유스케이스)과 `domain/service`(규칙)로 분리
4. Repository를 포트(interface)/어댑터(impl)로 분리
5. Controller·DTO를 `presentation`으로, 매핑 메서드 추가
6. 컴파일·테스트 통과 확인 후 다음 컨텍스트로. **패키지 이동은 Flyway 스키마와 무관**(테이블명 유지)하지만, `ddl-auto: validate`이므로 `@Table`/`@Column` 매핑이 기존 스키마와 일치하는지 확인한다.

## 10. 프로젝트 제약과의 정합

- **PK UUID** · **`ddl-auto: validate` + Flyway**: 엔티티 매핑은 마이그레이션 스키마와 정확히 일치.
- **`open-in-view: false`**: 지연로딩은 application service 트랜잭션 안에서 초기화. Response 매핑은 트랜잭션 종료 전에 끝내거나 필요한 데이터를 Result에 담아 나온다.
- **응답 포맷**: 성공 `ApiResponse.ok(...)`, 실패는 `BusinessException(ErrorCode)` → `GlobalExceptionHandler`. 도메인 규칙 위반도 `BusinessException`으로 던진다.
- **snake_case**: presentation DTO 필드에 적용(Jackson 전역).
- **OCR 트리거 경계**: 사고 입력 수신·S3 업로드·Kafka producer 발행은 report 컨텍스트의 application/infrastructure에 위치. OCR 실행·consumer는 FastAPI(범위 외).
```
