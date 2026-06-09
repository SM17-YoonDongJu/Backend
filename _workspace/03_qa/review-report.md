# QA 리뷰 리포트 — 채팅 기능

> 리뷰 대상: `com.soma.backend.domain.chat` 전체 + `global/security` + `infra/fcm` + `V2__create_chat_tables.sql`
> 기준 문서: `_workspace/01_analyst/design.md`, `02_security/summary.md`, `02_realtime/summary.md`
> 작성: qa-reviewer (독립 외부 검증)

---

## CRITICAL (즉시 수정 필요)

### C-1. SUBSCRIBE 시 멤버십 인가 부재 — 비멤버가 임의 방 메시지 도청 가능
[StompAuthChannelInterceptor.java:49-51, 101-103] / [ChatWebSocketConfig.java]
- design.md "WebSocket 계약 > 구독" 및 "qa-reviewer 경계 케이스 1"은 **비멤버의 `/topic/chat/{roomId}` 구독 차단**을 핵심 보안 요구로 명시한다.
- 현재 `StompAuthChannelInterceptor.requiresAuth()`는 SUBSCRIBE/SEND에 대해 **Principal 존재 여부만** 검사하고, SUBSCRIBE destination에서 `roomId`를 파싱해 멤버십을 확인하지 않는다.
- 결과: 인증된 아무 사용자나 `SUBSCRIBE /topic/chat/{임의 roomId}` 하면 해당 방의 모든 실시간 메시지를 수신한다. realtime-developer summary에도 "SUBSCRIBE 멤버십 인가: 미구현"으로 자인됨.
- 수정: `preSend`의 SUBSCRIBE 분기에서 `accessor.getDestination()`이 `/topic/chat/{roomId}` 패턴이면 roomId 추출 → `ChatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, principalUserId)` 검증, 비멤버면 `MessagingException(CHAT_NOT_A_MEMBER)`. ADMIN은 예외 허용. (인터셉터에서 Repository 주입 또는 경량 검증 컴포넌트 사용.)

### C-2. ADMIN 모더레이션 조회가 동작하지 않음 — 정합성 위반
[ChatRoomService.java:64-78 getRoom, ChatMessageService.java:30-31 getMessages]
- design.md API 2/6 및 가정 6은 "**ADMIN은 모든 방 조회 가능(모더레이션)**, 메시지/이력은 멤버십 필수, 단 ADMIN 예외"를 규정한다.
- 그러나 `getRoom`·`getMessages`는 호출자 role을 전혀 받지 않고 무조건 `assertMember()`를 호출한다. ADMIN이 비멤버 방을 조회하면 설계와 달리 `CHAT_NOT_A_MEMBER(403)`이 반환된다.
- `getRoom` 주석은 "ADMIN 모더레이션은 컨트롤러/시큐리티 계층에서 별도 처리"라고 적었으나, **컨트롤러에도 ADMIN 분기가 전혀 없다**(role 미전달). 즉 어느 계층에서도 구현되지 않은 미완성 기능.
- 수정: 컨트롤러에서 `principal.getRole()`을 서비스로 전달하고, 서비스가 `if (!"ADMIN".equals(role)) assertMember(...)` 형태로 ADMIN 우회. 또는 design에서 ADMIN 모더레이션을 명시적 범위 외로 내리고 문서/주석을 일치시킬 것. (정합성상 둘 중 하나 필수.)

### C-3. STOMP `chat.join` / `chat.leave`가 멤버십을 변경하지 않음
[ChatStompController.java:59-73]
- design.md "WebSocket 계약 > 발행 chat.join/chat.leave"와 realtime-developer 작업 2번은 "**멤버십 추가/제거 후** 시스템 메시지 브로드캐스트"를 명세한다.
- 현재 핸들러는 `ChatRoomService.joinRoom/leaveRoom`을 호출하지 않고 JOIN/LEAVE 시스템 메시지만 브로드캐스트한다. 즉 실제 입장/퇴장은 일어나지 않고, 비멤버도 임의 roomId로 JOIN/LEAVE 브로드캐스트를 주입할 수 있다(멤버십·방 존재 검증 전무).
- 수정: `chat.join` → `chatRoomService.joinRoom(roomId, userId)` 호출(중복/ DIRECT 금지 검증 재사용) 후 브로드캐스트. `chat.leave` → `leaveRoom` 호출 후 브로드캐스트. 또는 본 STOMP 입장/퇴장을 REST(API 4/5)로만 한정하고 design에서 해당 발행 채널을 제거.

---

## WARNING (릴리스 전 수정 권장)

### W-1. 존재하지 않는 roomId 처리 시 잘못된 에러 코드
[ChatRoomService.java:130-134 assertMember, ChatMessageService.java:52-53 saveMessage, getMessages:30-31]
- design.md 경계 케이스 3은 "존재하지 않는 roomId로 send/조회 → `CHAT_ROOM_NOT_FOUND`"를 요구한다.
- `getMessages`·`saveMessage`(STOMP send)는 방 존재 확인 없이 곧장 `assertMember`를 호출 → 없는 방이면 멤버 행도 없으므로 `CHAT_NOT_A_MEMBER(403)`이 반환된다. 기대값은 `CHAT_ROOM_NOT_FOUND(404)`.
- 수정: `assertMember` 진입 전 `chatRoomRepository.existsById(roomId)` 확인 후 없으면 `CHAT_ROOM_NOT_FOUND`. (정보 노출 정책상 의도적으로 403 통일하려면 design 문서를 수정해 일치시킬 것.)

### W-2. createRoom 응답 스키마가 설계 계약과 불일치
[RoomResponse.java:10-17 vs design.md API 1 Response]
- design.md API 1의 201 응답은 `{"room_id","title","type","member_ids":[...],"created_at"}` — `member_ids`는 **UUID 문자열 배열**.
- 구현 `RoomResponse`는 `members: List<MemberResponse>`(각 `{user_id, joined_at}` 객체) + `last_message` 필드를 포함한다. 필드명(`member_ids` vs `members`)과 형태(문자열 배열 vs 객체 배열)가 다르다.
- 클라이언트 계약 위반. 수정: 생성 응답 전용 DTO로 `member_ids` 배열을 반환하거나, design.md를 구현에 맞춰 갱신(권장: 조회 API 2와 형태 통일을 위해 design을 `members` 객체 배열로 정정). 어느 쪽이든 문서-코드 일치 필요.

### W-3. DIRECT 방 1명 퇴장 후 재생성 시 중복 방지 우회 가능
[ChatRoomMemberRepository.java:20-23 findDirectRoomByTwoUsers, ChatRoomService.java:120-127 leaveRoom]
- `findDirectRoomByTwoUsers`는 `HAVING COUNT(DISTINCT crm.userId) = 2`로 두 유저가 **모두 멤버로 남아있는** DIRECT 방만 중복으로 본다.
- A가 기존 A-B DIRECT 방에서 leave(멤버 행 삭제)하면, 같은 A-B로 DIRECT 방을 다시 생성할 때 기존 방(이제 멤버 1명)이 매칭되지 않아 **중복 방이 생성**된다. design 경계 케이스 9("DIRECT 1명 퇴장 후 동작 정의")가 미정의 상태로 남음.
- 빈 content/null은 잘 막지만 이 시나리오는 데이터 정합성 이슈. 수정: DIRECT 방 leave 정책 결정(예: DIRECT는 leave 금지 또는 방 deactivate) 후 중복 쿼리와 정합. 최소한 design에 동작을 명문화.

### W-4. FCM 트랜잭션 격리는 OK이나 메시지 저장 트랜잭션 경계가 STOMP 핸들러에 없음
[ChatStompController.java:49-57 sendMessage]
- 격리 자체는 양호: `pushOffline`이 try/catch로 FCM 예외를 삼키며, `saveMessage` 이후 호출되어 저장 롤백을 유발하지 않는다(설계 "푸시 격리" 충족).
- 다만 `saveMessage`(@Transactional)는 자체 트랜잭션에서 커밋되고, 그 뒤 `convertAndSend`·`pushOffline`은 트랜잭션 밖에서 실행된다 — "저장 성공 후 브로드캐스트"(가정 7) 순서는 지켜진다. 문제는 없으나, `saveMessage` 트랜잭션이 커밋되기 전 브로드캐스트되지 않도록 현재 순서(저장→리턴→send)가 의존하는 지점이라 회귀에 취약. 통합 테스트로 "저장 실패 시 미브로드캐스트"를 고정 권장.

### W-5. getRooms 커서 페이지네이션이 cursor 값을 사용하지 않음
[ChatRoomService.java:80-101 getRooms]
- `next_cursor`로 마지막 room id를 반환하지만, 다음 호출 시 전달된 `cursor` 파라미터를 쿼리에서 **전혀 사용하지 않는다**(`findByMemberUserId`는 cursor 미반영). 항상 첫 페이지만 조회되어 페이지네이션이 동작하지 않는다.
- 메시지 이력(ChatMessageService)의 커서는 올바르게 구현됨(아래 통과 항목 참고)이나 방 목록은 미완성. 수정: `updatedAt`(정렬 키) 기반 커서 쿼리 추가 또는 design에서 방 목록 커서를 범위 외로 명시.

---

## INFO (향후 개선)

- **I-1. `chat.send`의 `@Lazy ChatMessageService` 주입** [ChatStompController.java:42-43]: 순환 의존 회피용으로 보이나, 현재 의존 그래프상 순환이 없어 보인다(`ChatMessageService`는 STOMP 컨트롤러를 모른다). 불필요하면 `@Lazy` 제거로 시동 시 검증 강화 권장. 순환이 실제 존재한다면 주석으로 사유 기록.
- **I-2. `findByMemberUserId` N+1** [ChatRoomService.java:90-95]: 방 목록 각 room마다 `findTopByRoomIdOrderByCreatedAtDesc`를 루프 호출 → 방 N개에 쿼리 N+1회. 방이 많은 사용자에서 성능 저하. batch 조회 또는 lastMessage 역정규화 고려.
- **I-3. CORS Origin `*`** [ChatWebSocketConfig.java:30 setAllowedOriginPatterns("*")]: design은 "로컬은 `*` 비권장 → 명시 도메인, `cors.allowed-origins` 프로퍼티화"를 권고했으나 하드코딩 `*`. 운영 전 프로퍼티화 필요.
- **I-4. `chat.send` 멤버십 검증 위치**: `ChatMessageService.saveMessage`가 `assertMember`를 호출하므로 비멤버 send는 차단됨(양호). 다만 SUBSCRIBE(C-1)와 달리 SEND는 멤버십이 막히므로, C-1 수정 시 SEND도 동일 컴포넌트로 통합하면 일관성↑.
- **I-5. 다중 디바이스 presence**: `PresenceService`가 SADD/SREM 세션 집합 + TTL 안전망으로 design 경계 케이스 5/6을 정확히 충족. 다만 `connect` 시 매번 `expire`로 TTL을 갱신하나 `disconnect`/`remove` 후 집합이 비어도 키 자체는 TTL까지 잔존 — 기능 영향 없음(size 0이면 offline 판정). 개선 여지만 기록.
- **I-6. `MessageResponse.systemMessage` createdAt=Instant.now()**: 시스템 메시지는 DB 미저장이라 식별자/시각이 휘발적. JOIN/LEAVE를 영속화하려면 `ChatMessageService` 경유로 변경 고려(C-3 수정과 연계).

---

## 검증 통과 항목

- **DB 스키마 ↔ 엔티티 정합 (ddl-auto=validate 부팅 안전)**:
  - `TIMESTAMPTZ` ↔ `Instant` (created_at/updated_at/joined_at/last_read_at) 일치.
  - `VARCHAR(20)` ↔ `@Enumerated(STRING)` + `columnDefinition="VARCHAR(20)"` (ChatRoom.type, ChatMessage.type) 일치.
  - `TEXT` ↔ `String content` + `columnDefinition="TEXT"` 일치. `UUID` PK 전 컬럼 일치. `uq_room_member` 유니크 제약 일치.
- **sender 위변조 방지** [ChatStompController.java:50-51, 88-94]: `chat.send`의 sender는 페이로드가 아닌 `Principal`(CustomUserDetails)에서 추출. `ChatSendRequest`에 `senderId` 필드 없음 — 설계 핵심 보안 요구 충족.
- **이중 인증 구조**: HandshakeInterceptor(1차, 핸드셰이크 토큰 검증·session attr 저장) + StompAuthChannelInterceptor(2차, CONNECT 재검증·Principal 바인딩) 구성 일치. 토큰 없음/만료 시 `beforeHandshake` false 반환 → 핸드셰이크 거부(경계 케이스 2 충족).
- **SecurityConfig 경로 정책** [SecurityConfig.java:38-39]: `/ws/**` permitAll, `/api/chat/**` authenticated, STATELESS, CSRF disable, JwtFilter 선등록 — 설계 일치.
- **빈 content/null 방어** [ChatMessageService.java:54-55]: `content == null || isBlank()` → `CHAT_MESSAGE_EMPTY`. 경계 케이스 4 충족.
- **DIRECT 멤버 정확히 2인 검증** [ChatRoomService.java:136-141]: 요청 멤버 1명 + distinct 2명 위반 시 `CHAT_INVALID_MEMBER_COUNT`. 본인 중복 포함은 LinkedHashSet 중복 제거로 방어(경계 케이스 10 충족).
- **DIRECT 중복 방 생성 방지** [ChatRoomService.java:142-146]: `findDirectRoomByTwoUsers` 비어있지 않으면 `CHAT_DUPLICATE_DIRECT_ROOM` (단, W-3의 leave 후 우회 시나리오 제외).
- **DIRECT self-join 금지** [ChatRoomService.java:107-109]: `CHAT_DIRECT_JOIN_FORBIDDEN`. 중복 입장 `CHAT_ALREADY_MEMBER`.
- **비멤버 이력 조회 차단** [ChatMessageService.java:31]: `assertMember` → `CHAT_NOT_A_MEMBER`. 경계 케이스(비멤버 메시지 조회) 충족.
- **커서 페이지네이션 has_next 로직 (메시지 이력)** [ChatMessageService.java:34-49, ChatMessageRepository.java:15-25]: `limit+1` 조회 → `size > limit`로 hasNext 판정, 초과분 subList 제거, 복합 커서 `createdAt_id`로 동일 ms 충돌 방지(`createdAt < cursor OR (= AND id < cursorId)`, 정렬 `created_at DESC, id DESC`). 인덱스 `idx_chat_message_room_created (room_id, created_at DESC, id DESC)`와 정합 — 경계 케이스 7 충족.
- **잘못된 커서 방어** [ChatMessageService.java:61-74]: 파싱 실패 시 `INVALID_INPUT`.
- **ErrorCode ↔ 사용처**: `CHAT_ROOM_NOT_FOUND/NOT_A_MEMBER/ALREADY_MEMBER/DUPLICATE_DIRECT_ROOM/DIRECT_JOIN_FORBIDDEN/INVALID_MEMBER_COUNT/MESSAGE_EMPTY/WS_UNAUTHORIZED` 모두 실제 throw 위치 존재 확인. enum 세미콜론/콤마 문법 정상.
- **flat JSON + snake_case**: 모든 DTO record 필드 camelCase, `application.yml`에서 `property-naming-strategy: SNAKE_CASE` 전역 적용 → 직렬화 시 snake_case 변환. 래퍼 없음. ErrorResponse는 `{"error":{"code","message"}}` 설계 일치.
- **FCM 트랜잭션 격리** [ChatStompController.java:96-106, FcmService.java]: `pushOffline` try/catch로 FCM 예외 삼킴, `FcmService.send` 내부도 try/catch. FirebaseApp 미설정 시 stub. 메시지 저장 롤백 미유발 — 설계 충족(W-4는 회귀 방지용 권고).
- **오프라인 판정**: `presenceService.isOnline == false` 멤버만 FCM 대상, sender 제외. 설계 일치.
- **UUID PK 일관성**: 전 엔티티 PK·식별자 UUID. CustomUserDetails.userId UUID. 일관 유지.

---

## 요약 (리더 보고용)

- **CRITICAL 3건**은 모두 **보안/정합성 핵심**이며 릴리스 차단 사유:
  - C-1 SUBSCRIBE 멤버십 미검증(메시지 도청) — design 1순위 보안 요구 위반.
  - C-2 ADMIN 모더레이션 미구현(어느 계층에도 없음).
  - C-3 STOMP join/leave가 실제 멤버십을 바꾸지 않음(비멤버 이벤트 주입 가능).
- WARNING 5건은 API 계약 불일치(W-2)·에러코드(W-1)·DIRECT 재생성(W-3)·방목록 커서 미동작(W-5) 등 기능 완성도 이슈.
- 통과 항목: DDL↔엔티티 정합, sender 위변조 방지, 이중 인증, 메시지 커서 페이지네이션, snake_case/flat JSON, FCM 격리 등 핵심 골격은 견고.
- 권장 후속: spring-qa 스킬 기반 단위/통합 테스트 작성 — 특히 C-1/C-2/C-3 회귀 방지 테스트와 메시지 커서 경계 테스트, TestContainers(PostgreSQL)로 V2 마이그레이션·validate 부팅 검증(현재 build.gradle에 TestContainers 의존성 부재 → 추가 필요).
