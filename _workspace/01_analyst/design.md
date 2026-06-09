# 설계 문서: WebSocket(STOMP) 채팅 기능

> 입력: `_workspace/00_input/request.md`
> 담당 도메인 패키지: `com.soma.backend.domain.chat`
> 작성: backend-analyst

---

## 가정 목록

> 코드베이스 탐색 결과, CLAUDE.md가 묘사하는 인프라 상당수가 **소스에 아직 존재하지 않는다**.
> 실제로 존재하는 코드는 `global/exception/*`, `global/config/RedisConfig`, `global/config/S3Config`, `BackendApplication`, `application.yml`, `application-oauth.yml` 뿐이다.
> 아래 가정은 이 격차를 메우기 위한 합리적 전제이며, 리더/security-developer가 검토해야 한다.

1. **[중대] 보안 인프라 부재** — `JwtProvider`, `JwtFilter`, `CustomUserDetails`, `SecurityConfig`가 소스에 없다. (`.claude/skills/spring-security-impl`, `agents/security-developer.md`에만 명세 존재). 본 채팅 기능은 JWT 인증에 강하게 의존하므로, **security-developer가 최소한의 `JwtProvider`(토큰 파싱 → userId, role 추출)와 `CustomUserDetails`를 먼저 제공**한다고 가정한다. 본 설계는 그 인터페이스를 다음과 같이 전제한다:
   - `JwtProvider.validateToken(String token): boolean`
   - `JwtProvider.getUserId(String token): UUID`
   - `JwtProvider.getRole(String token): String` (USER / ADJUSTER / ADMIN)
   - `CustomUserDetails.getUserId(): UUID`
2. **[중대] FCM 인프라 부재** — `infra/fcm` 패키지가 소스에 없다. 오프라인 푸시를 위해 **`FcmService.send(UUID receiverUserId, String title, String body, Map<String,String> data)` 형태의 서비스가 제공된다고 가정**한다. 없으면 realtime-developer가 firebase-admin(이미 build.gradle에 의존성 존재) 기반 최소 구현을 포함한다.
3. **[중대] users 테이블 부재** — `V1__init_schema.sql`은 주석만 있고 비어 있다. `users` 테이블이 아직 없으므로, FK를 거는 대신 **`chat_message.sender_id`, `chat_room_member.user_id`는 FK 없는 UUID 컬럼**으로 설계한다. (users 테이블 생성 시점에 별도 마이그레이션으로 FK 추가). 이로써 채팅 마이그레이션이 users 마이그레이션 순서에 종속되지 않는다.
4. **온라인 상태 판별** — "WebSocket 미연결(오프라인)" 판단은 **Redis에 접속 세션을 기록**하여 수행한다고 가정한다. 키: `ws:online:{userId}` (STOMP CONNECT 시 SET, DISCONNECT 시 DEL, TTL 안전망 포함). 기존 `RedisTemplate<String,String>` Bean 재사용.
5. **채팅방 타입(`type`)** — `DIRECT`(1:1), `GROUP` 두 가지로 가정. 매칭 도메인 연동은 범위 외로 두고 `type` enum만 정의한다.
6. **권한 모델** — 채팅은 역할(USER/ADJUSTER/ADMIN) 무관하게 **"채팅방 멤버인지"** 로 인가한다. 단 ADMIN은 모든 방 조회 가능(모더레이션)으로 가정. 메시지 전송/이력 조회는 멤버십 필수.
7. **메시지 영속화 시점** — STOMP `/app/chat.send` 수신 시 **먼저 DB 저장 후 `/topic/chat/{roomId}` 브로드캐스트**. 저장 실패 시 브로드캐스트하지 않는다.
8. **커서 페이지네이션** — 메시지 이력은 `created_at` 역순, 커서는 마지막으로 받은 `message_id`(또는 created_at) 기준. 본 설계는 `created_at + id` 복합 커서를 사용한다(동일 ms 충돌 방지).
9. **simple broker 사용** — 외부 메시지 브로커(RabbitMQ/Kafka STOMP relay) 없이 **인메모리 SimpleBroker** 사용 가정. (다중 인스턴스 확장은 범위 외, 가정 4의 Redis 온라인 키만 공유)
10. **Jackson 전역 snake_case** 적용됨(application.yml 확인). DTO 필드는 camelCase로 작성해도 직렬화 시 snake_case로 변환된다.

---

## API 계약

공통:
- 인증: `Authorization: Bearer {accessToken}` (JwtFilter가 SecurityContext에 CustomUserDetails 주입)
- 응답: flat JSON, snake_case (래퍼 없음)
- 오류: `{ "error": { "code", "message" } }` — `GlobalExceptionHandler` 처리
- 식별자: 모두 UUID (문자열 직렬화)

### 1. 채팅방 생성
`POST /api/chat/rooms` — 권한: USER, ADJUSTER, ADMIN (인증 필수)

Request Body:
```json
{
  "title": "홍길동님과의 상담",
  "type": "DIRECT",
  "member_ids": ["uuid-a", "uuid-b"]
}
```
- `title`: 1~100자, GROUP 타입은 필수 / DIRECT는 선택(null 허용)
- `type`: `DIRECT` | `GROUP`
- `member_ids`: 생성자 외 초대할 유저 UUID 목록. DIRECT는 정확히 1명. 생성자는 자동 멤버 추가.

Response (201):
```json
{
  "room_id": "uuid",
  "title": "홍길동님과의 상담",
  "type": "DIRECT",
  "member_ids": ["creator-uuid", "uuid-b"],
  "created_at": "2026-06-09T12:00:00Z"
}
```
오류: `INVALID_INPUT`(타입/멤버 수 위반), `UNAUTHORIZED`, `CHAT_DUPLICATE_DIRECT_ROOM`(동일 2인 DIRECT 중복 시)

### 2. 채팅방 조회
`GET /api/chat/rooms/{roomId}` — 권한: 해당 방 멤버 또는 ADMIN

Response (200):
```json
{
  "room_id": "uuid",
  "title": "...",
  "type": "GROUP",
  "members": [
    { "user_id": "uuid", "joined_at": "2026-06-09T12:00:00Z" }
  ],
  "last_message": { "message_id": "uuid", "content": "...", "created_at": "..." },
  "created_at": "..."
}
```
오류: `CHAT_ROOM_NOT_FOUND`, `CHAT_NOT_A_MEMBER`(멤버 아님), `UNAUTHORIZED`

### 3. 내 채팅방 목록
`GET /api/chat/rooms` — 권한: 인증된 본인

Query Params: `limit`(기본 20), `cursor`(선택, 마지막 room의 정렬 키)

Response (200):
```json
{
  "rooms": [
    {
      "room_id": "uuid",
      "title": "...",
      "type": "DIRECT",
      "last_message": { "content": "...", "created_at": "..." },
      "unread_count": 3
    }
  ],
  "next_cursor": "uuid-or-null"
}
```
- 정렬: 마지막 메시지 시각 역순(없으면 방 생성 시각)
- `unread_count`: 가정상 본 설계 1차 범위에서는 계산 생략 가능(항상 0). 경계 케이스 참고. **(범위 외 표시)**

### 4. 채팅방 입장
`POST /api/chat/rooms/{roomId}/members` — 권한: 인증된 본인 (GROUP만 self-join 허용)

Request Body: 없음 (본인이 입장). 호출자 = 멤버로 추가.

Response (200):
```json
{ "room_id": "uuid", "user_id": "uuid", "joined_at": "..." }
```
오류: `CHAT_ROOM_NOT_FOUND`, `CHAT_ALREADY_MEMBER`, `CHAT_DIRECT_JOIN_FORBIDDEN`(DIRECT 방은 self-join 금지)

### 5. 채팅방 퇴장
`DELETE /api/chat/rooms/{roomId}/members/me` — 권한: 해당 방 멤버

Response: 204 No Content
오류: `CHAT_ROOM_NOT_FOUND`, `CHAT_NOT_A_MEMBER`
- 퇴장 시 시스템 메시지 브로드캐스트(`/topic/chat/{roomId}`, type=`LEAVE`) 권장.

### 6. 채팅 이력 (커서 페이지네이션)
`GET /api/chat/rooms/{roomId}/messages` — 권한: 해당 방 멤버 또는 ADMIN

Query Params:
- `limit`: 기본 30, 최대 100
- `cursor`: 마지막으로 받은 메시지의 `created_at` ISO 문자열(또는 message_id). 없으면 최신부터.

Response (200):
```json
{
  "messages": [
    {
      "message_id": "uuid",
      "room_id": "uuid",
      "sender_id": "uuid",
      "type": "TALK",
      "content": "안녕하세요",
      "created_at": "2026-06-09T12:00:00Z"
    }
  ],
  "next_cursor": "2026-06-09T11:59:00Z",
  "has_next": true
}
```
- 정렬: `created_at DESC, id DESC`
오류: `CHAT_ROOM_NOT_FOUND`, `CHAT_NOT_A_MEMBER`

---

## WebSocket 계약 (STOMP)

### STOMP 설정 (`ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer`)
- 연결 엔드포인트: `registerStompEndpoints` → `/ws` (+ `withSockJS()` 선택)
- 애플리케이션 prefix: `/app` (`setApplicationDestinationPrefixes("/app")`)
- 브로커: `enableSimpleBroker("/topic")` (인메모리 SimpleBroker, 가정 9)
- 허용 Origin: 프로퍼티화(`cors.allowed-origins`), 로컬은 `*` 비권장 → 명시 도메인

### 인증 (HandshakeInterceptor + ChannelInterceptor)
- 1차: **`JwtHandshakeInterceptor implements HandshakeInterceptor`** — HTTP 핸드셰이크 시 query param `?token=` 또는 `Authorization` 헤더에서 JWT 추출 → `JwtProvider.validateToken` → userId/role을 WebSocket session attributes에 저장. 실패 시 핸드셰이크 거부(401).
- 2차(권장): **`StompAuthChannelInterceptor implements ChannelInterceptor`** — CONNECT 프레임의 `Authorization` STOMP 헤더 재검증, `Principal`(userId) 바인딩. SEND 시 sender 위변조 방지.
- 온라인 상태: CONNECT 시 `ws:online:{userId}` SET, DISCONNECT(`SessionDisconnectEvent`) 시 DEL. (가정 4)

### 메시지 발행/구독 포맷

발행 `/app/chat.send` (메시지 전송):
```json
{ "room_id": "uuid", "content": "안녕하세요" }
```
- 서버 처리: 멤버십 검증 → DB 저장 → `/topic/chat/{roomId}`로 브로드캐스트 → 오프라인 멤버에게 FCM.
- `sender_id`는 클라이언트 입력을 신뢰하지 않고 **Principal(인증 세션)에서 추출**.

발행 `/app/chat.join` / `/app/chat.leave` (입장/퇴장 이벤트):
```json
{ "room_id": "uuid" }
```
- 멤버십 추가/제거 후 시스템 메시지 브로드캐스트.

구독 `/topic/chat/{roomId}` (실시간 수신) — 브로드캐스트 페이로드:
```json
{
  "message_id": "uuid",
  "room_id": "uuid",
  "sender_id": "uuid",
  "type": "TALK",
  "content": "안녕하세요",
  "created_at": "2026-06-09T12:00:00Z"
}
```
- `type`: `TALK` | `JOIN` | `LEAVE`
- 구독 시점에 멤버십 검증(ChannelInterceptor의 SUBSCRIBE 프레임 검사)을 권장 — 비멤버가 임의 방 구독 차단.

### WebSocket 오류 전달
- 처리 실패 시 `/user/queue/errors`(user-destination)로 오류 전송 또는 `@MessageExceptionHandler`로 `ErrorResponse` 유사 포맷 반환.

---

## DB 스키마

> 다음 마이그레이션 버전: **`V2`** (현재 `V1__init_schema.sql`만 존재, 내용은 주석뿐).
> 파일: `src/main/resources/db/migration/V2__create_chat_tables.sql`
> 모든 변경은 **신규 테이블 생성**이며 기존 데이터 영향 없음(컬럼 삭제/타입변경 없음).
> users 테이블 부재로 `sender_id`/`user_id`에 **FK 미설정**(가정 3). users 생성 후 별도 마이그레이션에서 FK 보강 권장.

```sql
-- V2__create_chat_tables.sql

CREATE TABLE chat_room (
    id          UUID         PRIMARY KEY,
    title       VARCHAR(100),
    type        VARCHAR(20)  NOT NULL,            -- DIRECT | GROUP
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE chat_room_member (
    id          UUID         PRIMARY KEY,
    room_id     UUID         NOT NULL REFERENCES chat_room(id) ON DELETE CASCADE,
    user_id     UUID         NOT NULL,            -- FK 없음(users 테이블 부재, 가정 3)
    joined_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_read_at TIMESTAMPTZ,                     -- unread 계산용(향후)
    CONSTRAINT uq_room_member UNIQUE (room_id, user_id)
);

CREATE TABLE chat_message (
    id          UUID         PRIMARY KEY,
    room_id     UUID         NOT NULL REFERENCES chat_room(id) ON DELETE CASCADE,
    sender_id   UUID         NOT NULL,            -- FK 없음(가정 3)
    type        VARCHAR(20)  NOT NULL DEFAULT 'TALK',  -- TALK | JOIN | LEAVE
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 인덱스
CREATE INDEX idx_chat_member_user      ON chat_room_member (user_id);
CREATE INDEX idx_chat_member_room      ON chat_room_member (room_id);
-- 이력 커서 페이지네이션: (room_id, created_at DESC, id DESC)
CREATE INDEX idx_chat_message_room_created ON chat_message (room_id, created_at DESC, id DESC);
```

- 엔티티 매핑: `@Id UUID`, `@Enumerated(EnumType.STRING)` for type, `created_at`은 `@CreationTimestamp` 또는 DB default. ddl-auto=validate이므로 **DDL과 엔티티 컬럼이 정확히 일치해야 함**(특히 TIMESTAMPTZ ↔ `Instant`/`OffsetDateTime`).

---

## ErrorCode 추가 목록

`global/exception/ErrorCode.java`에 `// Chat` 섹션 추가:

```java
// Chat
CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
CHAT_NOT_A_MEMBER(HttpStatus.FORBIDDEN, "채팅방 멤버가 아닙니다."),
CHAT_ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 채팅방 멤버입니다."),
CHAT_DUPLICATE_DIRECT_ROOM(HttpStatus.CONFLICT, "이미 존재하는 1:1 채팅방입니다."),
CHAT_DIRECT_JOIN_FORBIDDEN(HttpStatus.FORBIDDEN, "1:1 채팅방에는 입장할 수 없습니다."),
CHAT_INVALID_MEMBER_COUNT(HttpStatus.BAD_REQUEST, "채팅방 멤버 구성이 올바르지 않습니다."),
CHAT_MESSAGE_EMPTY(HttpStatus.BAD_REQUEST, "메시지 내용이 비어 있습니다."),
CHAT_WS_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "WebSocket 인증에 실패했습니다.");
```
- 기존 enum은 마지막 항목(`SUBSCRIPTION_NOT_FOUND`)이 세미콜론으로 끝나므로, 그 앞 항목 끝을 콤마로 바꾸거나 채팅 항목을 그 뒤에 삽입 후 세미콜론 위치 조정 필요(편집 시 주의).

---

## backend-developer 작업

REST API + JPA 영속 계층 담당. 구현 순서:

1. **엔티티** (`domain/chat/entity` 또는 `domain/chat/domain`)
   - `ChatRoom`, `ChatRoomMember`, `ChatMessage`, enum `ChatRoomType`, `ChatMessageType`
2. **Repository** (`domain/chat/repository`)
   - `ChatRoomRepository`, `ChatRoomMemberRepository`(멤버십 조회/존재 확인), `ChatMessageRepository`(커서 페이지네이션 쿼리)
3. **DTO** (`domain/chat/dto`)
   - Request: `CreateRoomRequest`
   - Response: `RoomResponse`, `RoomSummaryResponse`, `RoomListResponse`, `MessageResponse`, `MessageHistoryResponse`, `MemberResponse`
4. **Service** (`domain/chat/service`)
   - `ChatRoomService`(생성/조회/목록/입장/퇴장 + 멤버십 검증 로직), `ChatMessageService`(이력 조회, 메시지 저장)
   - 멤버십 검증 공통 메서드: `assertMember(roomId, userId)` → 비멤버 시 `CHAT_NOT_A_MEMBER`
5. **Controller** (`domain/chat/controller`)
   - `ChatRoomController`(API 1~5), `ChatMessageController`(API 6)
   - `@AuthenticationPrincipal CustomUserDetails`로 호출자 식별
6. **ErrorCode 추가** (위 목록)
7. **Flyway** `V2__create_chat_tables.sql`

의존: 가정 1의 `CustomUserDetails`가 필요. 없으면 security-developer 산출물 대기.

---

## security-developer 작업

WebSocket/REST 인증 경계 담당.

1. **[선행/중대] 최소 JWT 인프라 확인·제공** — `JwtProvider`, `CustomUserDetails`, `SecurityConfig`가 소스에 없으므로(가정 1), 다음을 우선 제공:
   - `JwtProvider` (validate / getUserId / getRole) — `application.yml`의 `jwt.secret`, `jwt.*-expiry` 사용
   - `CustomUserDetails`(userId: UUID, role: String)
   - `SecurityConfig` — `/ws/**`는 핸드셰이크 인터셉터로 인증하므로 HTTP 시큐리티에서는 permitAll, `/api/chat/**`는 authenticated
2. **`JwtHandshakeInterceptor`** (`domain/chat/config` 또는 `global/security`) — 핸드셰이크 시 토큰 검증, session attribute에 userId/role 저장
3. **`StompAuthChannelInterceptor`** — CONNECT/SUBSCRIBE/SEND 프레임 인증·인가(Principal 바인딩, 구독 방 멤버십 검사)
4. `CHAT_WS_UNAUTHORIZED` 등 인증 오류 처리 + `/user/queue/errors` 전달 설계
5. CORS/Origin 허용 정책(`cors.allowed-origins` 프로퍼티)

의존: realtime-developer의 `ChatWebSocketConfig`와 인터셉터 등록 지점 협의.

---

## realtime-developer 작업

WebSocket 메시징 + FCM 오프라인 푸시 담당. 구현 순서:

1. **`ChatWebSocketConfig`** (`domain/chat/config`) — `@EnableWebSocketMessageBroker`, `/ws` 엔드포인트, `/app` prefix, `/topic` SimpleBroker, 인터셉터 등록(security-developer 인터셉터 결합)
2. **STOMP 컨트롤러** (`domain/chat/controller`) — `ChatStompController`
   - `@MessageMapping("chat.send")` → 멤버십 검증 → `ChatMessageService.save` → `SimpMessagingTemplate.convertAndSend("/topic/chat/{roomId}", payload)`
   - `@MessageMapping("chat.join")`, `@MessageMapping("chat.leave")` → 멤버십 변경 + 시스템 메시지 브로드캐스트
   - `@MessageExceptionHandler` → 오류를 `/user/queue/errors`로
3. **온라인 상태 추적** (`domain/chat/presence` 또는 infra)
   - `WebSocketEventListener`(`SessionConnectedEvent`/`SessionDisconnectEvent`) → Redis `ws:online:{userId}` SET/DEL (기존 `RedisTemplate<String,String>` 재사용)
   - `PresenceService.isOnline(userId): boolean`
4. **FCM 오프라인 푸시** (`infra/fcm` 신규 또는 가정 2의 기존 활용)
   - `FcmService.send(...)` — firebase-admin(build.gradle 존재) 기반. 없으면 최소 구현 포함.
   - 메시지 저장 후 방 멤버 중 `sender 제외 && PresenceService.isOnline == false` 대상에게 푸시
   - 푸시 실패는 채팅 트랜잭션과 분리(예외 삼킴 + 로깅) — 메시지 전송이 푸시 실패로 롤백되지 않도록

의존: backend-developer의 `ChatMessageService`/엔티티, security-developer의 인터셉터.

---

## qa-reviewer 참고

### 핵심 비즈니스 규칙
- **멤버십 인가**: 방 조회·이력 조회·메시지 전송·구독은 모두 멤버여야 한다. ADMIN만 비멤버 조회 허용(모더레이션).
- **sender 위변조 방지**: STOMP `chat.send`의 sender는 클라이언트 페이로드가 아니라 인증 Principal에서 결정.
- **DIRECT 규칙**: 정확히 2인, self-join 금지(`CHAT_DIRECT_JOIN_FORBIDDEN`), 동일 2인 중복 생성 금지(`CHAT_DUPLICATE_DIRECT_ROOM`).
- **저장 우선**: 메시지는 DB 저장 성공 후에만 브로드캐스트(가정 7).
- **푸시 격리**: FCM 실패가 메시지 전송/저장을 롤백시키면 안 된다.
- **오프라인 판정**: `ws:online:{userId}` 부재 = 오프라인 → FCM 대상.

### 경계 케이스
1. 비멤버가 `/topic/chat/{roomId}` 구독 시도 → 차단 확인
2. 토큰 없이/만료 토큰으로 `/ws` 핸드셰이크 → 401 거부
3. 존재하지 않는 roomId로 send/조회 → `CHAT_ROOM_NOT_FOUND`
4. 빈 문자열/공백/초과 길이 content 전송 → `CHAT_MESSAGE_EMPTY` / 검증
5. 동시에 같은 유저가 다중 디바이스 연결 → online 키 카운팅(SET 카운터 또는 디바이스별 키) 필요성 검토. 단순 SET/DEL이면 한 디바이스 종료가 online을 꺼버리는 버그 가능 → **권장: `ws:online:{userId}`를 세션ID 집합(SADD/SREM)으로 관리**.
6. DISCONNECT 이벤트 누락(네트워크 단절) → online 키 TTL 안전망
7. 커서 페이지네이션: 동일 `created_at`(ms 충돌) 메시지 다수 → `id` 보조 정렬로 중복/누락 방지
8. 퇴장 후 같은 방 재입장(GROUP) → `last_read_at`/멤버십 재생성 처리
9. DIRECT 방 멤버 1명 퇴장 후 메시지 전송 → 남은 멤버 동작 정의
10. 자기 자신에게만 멤버인 방, member_ids에 본인 중복 포함 → 중복 제거 검증
11. ddl-auto=validate: 엔티티 컬럼 타입과 V2 DDL 불일치 시 부팅 실패 → 통합 테스트(TestContainers)로 검증 권장

### 테스트 권장(spring-qa 스킬 참조)
- 멤버십 검증 단위 테스트(비멤버 접근 차단)
- MockMvc로 REST API 6종 happy/error path
- STOMP 통합 테스트(`@SpringBootTest` + StandardWebSocketClient)로 send→구독 수신
- TestContainers(PostgreSQL)로 V2 마이그레이션 + 커서 페이지네이션 검증

---

## 참조할 기존 코드 경로
- 예외 패턴: `src/main/java/com/soma/backend/global/exception/{ErrorCode,BusinessException,GlobalExceptionHandler,ErrorResponse}.java`
- Redis Bean(온라인 상태/세션): `src/main/java/com/soma/backend/global/config/RedisConfig.java` (`RedisTemplate<String,String>`)
- S3 조건부 Bean 패턴 참고: `src/main/java/com/soma/backend/global/config/S3Config.java`
- 전역 설정(JWT, Jackson snake_case, JPA validate, flyway): `src/main/resources/application.yml`
- Flyway 다음 버전 기준: `src/main/resources/db/migration/V1__init_schema.sql` → 다음 `V2`
- 의존성 확인: `build.gradle` (websocket, firebase-admin, data-redis 모두 존재)
