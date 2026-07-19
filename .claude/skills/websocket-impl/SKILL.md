---
name: websocket-impl
description: "Spring Boot WebSocket(STOMP) 채팅 구현 가이드. WebSocketConfig(/ws-chat, SimpleBroker /topic 구독 전용), 쿠키 기반 핸드셰이크 인증, SUBSCRIBE 참여자 인가, REST 전송 + Redis pub/sub relay, ChatRoom·ChatMessage 엔티티(읽음 커서·jsonb 첨부), QueryDSL 커서 페이지네이션 패턴 정의. realtime-developer 에이전트가 참조."
---

# WebSocket(STOMP) 구현 가이드

이 프로젝트의 WebSocket 채팅 구현 패턴을 정의한다. 실제 코드(`global/config/WebSocketConfig`,
`global/security/Chat*`, `domain/chat/**`, `infra/redis/Chat*`)와 정합하게 유지한다.

**아키텍처 요지(3가지가 핵심):**
1. **인증은 access_token 쿠키.** 쿼리 파라미터 토큰이 아니라 HttpOnly `access_token` 쿠키를 핸드셰이크에서 읽어 `jwtProvider.validate(...)`로 검증한다.
2. **전송은 REST + Redis pub/sub.** STOMP `@MessageMapping`을 **두지 않는다.** 클라이언트는 브로커를 **구독만** 하고, 메시지 전송은 REST(`POST /chats/{id}/messages`)로 한다. 저장 커밋 후 Redis 채널로 발행 → 전 인스턴스가 구독해 STOMP `/topic`으로 relay 한다.
3. **브로커는 SimpleBroker `/topic` 단일**, 엔드포인트는 `/ws-chat`이다.

## 의존성

```gradle
implementation 'org.springframework.boot:spring-boot-starter-websocket'
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

## WebSocketConfig (`global/config/WebSocketConfig.java`)

- 엔드포인트: **`/ws-chat`**(native WebSocket, SockJS 미사용)
- 브로커: **`enableSimpleBroker("/topic")` 단일**(`/queue` 없음), 앱 prefix `/app`
- 오리진: 와일드카드 `*`가 아니라 앱 CORS 패턴(`app.cors.allowed-origin-patterns`) 재사용 — 쿠키 인증이라 오리진을 좁힌다
- 핸드셰이크 인증(`ChatHandshakeInterceptor` + `ChatHandshakeHandler`)과 SUBSCRIBE 인가(`ChatSubscribeInterceptor`)를 배선한다. 세 컴포넌트 모두 `@Component`라 생성자 주입한다(`new`로 만들지 않는다).

```java
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final ChatHandshakeInterceptor chatHandshakeInterceptor;
  private final ChatHandshakeHandler chatHandshakeHandler;
  private final ChatSubscribeInterceptor chatSubscribeInterceptor;

  @Value("${app.cors.allowed-origin-patterns}")
  private List<String> allowedOriginPatterns;

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.enableSimpleBroker("/topic");           // 구독 전용
    registry.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws-chat")
        .setAllowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]))
        .addInterceptors(chatHandshakeInterceptor)
        .setHandshakeHandler(chatHandshakeHandler);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(chatSubscribeInterceptor);   // SUBSCRIBE 참여자 인가
  }
}
```

> **전송에 `@MessageMapping`을 두지 않는 이유:** 메시지 저장·비정규화·afterCommit 발행을 트랜잭션 경계 안에서
> 다루기 위해 전송은 REST(③)로 처리한다. 브로커는 오직 구독·relay 통로다.

## 쿠키 기반 핸드셰이크 인증

WebSocket 연결 시 **`access_token` HttpOnly 쿠키**의 JWT를 검증한다(REST와 동일한 쿠키). 쿼리 파라미터 토큰은
쓰지 않는다. 검증 메서드는 `jwtProvider.validate(token)`이다(`validateToken`은 존재하지 않음). 세 컴포넌트로 나뉜다.

### 1) ChatHandshakeInterceptor (`global/security/ChatHandshakeInterceptor.java`)

핸드셰이크 시 쿠키의 userId를 세션 attribute(`chatUserId`)에 심고, 실패 시 401로 거부한다.

```java
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

  public static final String USER_ID_ATTRIBUTE = "chatUserId";

  private final CookieProvider cookieProvider;
  private final JwtProvider jwtProvider;

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler wsHandler, Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    Optional<String> token = cookieProvider.readCookie(
        servletRequest.getServletRequest(), CookieProvider.ACCESS_TOKEN_COOKIE);
    if (token.isEmpty()) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    try {
      jwtProvider.validate(token.get());                       // validateToken 아님
      attributes.put(USER_ID_ATTRIBUTE, jwtProvider.getUserId(token.get()));
      return true;
    } catch (BusinessException ex) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);         // 위조·만료 → 핸드셰이크 거부
      return false;
    }
  }
}
```

### 2) ChatHandshakeHandler (`global/security/ChatHandshakeHandler.java`)

세션 attribute의 userId를 STOMP Principal(name=userId)로 승격한다. 이후 SUBSCRIBE 인가가 이 Principal을 쓴다.

```java
@Component
public class ChatHandshakeHandler extends DefaultHandshakeHandler {

  @Override
  protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    Object userId = attributes.get(ChatHandshakeInterceptor.USER_ID_ATTRIBUTE);
    return userId == null ? null : new ChatPrincipal(userId.toString());
  }
}
```

### 3) ChatSubscribeInterceptor (`global/security/ChatSubscribeInterceptor.java`)

SUBSCRIBE 프레임을 가로채, `/topic/chat.rooms.{roomId}` 구독자가 해당 방 참여자(`room.isMember`)인지 검증하고
아니면 구독을 거부한다. 클라이언트는 구독만 하므로 SEND는 다루지 않는다.

```java
@Component
@RequiredArgsConstructor
public class ChatSubscribeInterceptor implements ChannelInterceptor {

  private static final String ROOM_DESTINATION_PREFIX = "/topic/chat.rooms.";
  private final ChatRoomRepository chatRoomRepository;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      return message;
    }
    String destination = accessor.getDestination();
    if (destination == null || !destination.startsWith(ROOM_DESTINATION_PREFIX)) {
      return message;
    }
    Principal user = accessor.getUser();
    if (user == null) {
      throw new BusinessException(ErrorCode.CHAT_WS_UNAUTHORIZED);
    }
    UUID me = UUID.fromString(user.getName());
    UUID roomId = UUID.fromString(destination.substring(ROOM_DESTINATION_PREFIX.length()));
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isMember(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
    return message;
  }
}
```

## 메시지 전송 = REST + Redis pub/sub relay

전송은 STOMP가 아니라 REST다. `POST /chats/{chatRoomId}/messages` → `ChatMessageCommandService.send()`가
① 참여자·방 상태(CLOSED면 `CHAT_ROOM_CLOSED`) 검증 → ② 저장 + `room.touchLastMessage(...)` 비정규화 →
③ **트랜잭션 커밋 후** Redis 채널로 브로드캐스트 발행(`publishAfterCommit` — 롤백 시 미발행)한다.

```java
@Transactional
public ChatMessageResponse send(UUID me, UUID roomId, SendMessageRequest request) {
  ChatRoom room = chatRoomRepository.findById(roomId)
      .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
  if (!room.isMember(me)) {
    throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
  }
  if (room.getStatus() == ChatRoomStatus.CLOSED) {
    throw new BusinessException(ErrorCode.CHAT_ROOM_CLOSED);
  }
  // content·attachments 중 최소 하나 필수 (없으면 CHAT_MESSAGE_EMPTY)
  ChatMessage saved = chatMessageRepository.save(/* text 또는 attachment 메시지 */);
  room.touchLastMessage(preview, saved.getCreatedAt());
  chatEventPublisher.publishAfterCommit(toBroadcast(saved));   // afterCommit 발행
  return toResponse(saved, roomId, me);
}
```

### relay 흐름 (`infra/redis/`)

- **`ChatEventPublisher.publishAfterCommit`**: 활성 트랜잭션이 있으면 `TransactionSynchronization.afterCommit`에서, 없으면 즉시 `redisTemplate.convertAndSend(RedisConfig.CHAT_MESSAGE_CHANNEL, json)`로 발행한다. 채널명은 `chat.message`.
- **`ChatMessageSubscriber implements MessageListener`**: `RedisMessageListenerContainer`(RedisConfig)가 채널 구독으로 배선한다. 발행분을 역직렬화해 `SimpMessagingTemplate.convertAndSend("/topic/chat.rooms." + roomId, ...)`로 STOMP relay 한다. relay 실패는 로그만 남기고 삼킨다(메시지는 이미 DB에 영속).
- 전송 인스턴스 자신을 포함한 **모든 인스턴스가 이 relay를 통해서만** 브로드캐스트하므로, 각 세션은 메시지를 정확히 한 번 수신한다(멀티 인스턴스 팬아웃). 클라이언트는 `sender_id`로 본인 메시지 여부를 판별한다.
- JSON 직렬화는 Jackson 3(`tools.jackson.databind.json.JsonMapper`)를 쓴다(Boot 4 기본).

## 엔티티 패턴

PK는 UUID(`@GeneratedValue`, strategy 미지정), `snake_case` 컬럼, Flyway 마이그레이션 준수. 리치 도메인 모델
(정적 팩터리·상태 전이 메서드·불변식)을 엔티티 안에서 챙긴다.

### ChatRoom (`domain/chat/entity/ChatRoom.java`, `@Table("chatroom")`)

멤버십은 **2자(user_id·adjuster_id) 컬럼**이다 — 별도 `chat_room_member` 조인 테이블을 두지 않는다(1:1 상담방).
`status`(ACTIVE/CLOSED)는 방 생명주기만 나타내고, 수락/거절 결정은 `REPORT_REVIEWS.status`가 소유한다(중복 금지).
읽음은 참여자별 커서(`user_last_read_at`·`adjuster_last_read_at`)로 관리한다.

```java
@Entity
@Table(name = "chatroom")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseEntity {

  @Id @GeneratedValue private UUID id;
  @Column(name = "user_id", nullable = false) private UUID userId;
  @Column(name = "adjuster_id", nullable = false) private UUID adjusterId;
  @Column(name = "report_id") private UUID reportId;
  @Column(name = "report_review_id") private UUID reportReviewId;   // 공유 리포트(파이프라인 경로만 set)

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20) private ChatRoomStatus status;   // ACTIVE / CLOSED

  @Column(name = "last_message") private String lastMessage;
  @Column(name = "last_message_at") private LocalDateTime lastMessageAt;
  @Column(name = "user_last_read_at") private LocalDateTime userLastReadAt;
  @Column(name = "adjuster_last_read_at") private LocalDateTime adjusterLastReadAt;

  public boolean isMember(UUID accountId) {                          // 인가 가드
    return accountId != null && (accountId.equals(userId) || accountId.equals(adjusterId));
  }

  public void markRead(UUID readerId, LocalDateTime at) {            // 본인 쪽 커서만 갱신
    if (readerId.equals(userId)) { this.userLastReadAt = at; }
    else if (readerId.equals(adjusterId)) { this.adjusterLastReadAt = at; }
    else { throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER); }
  }

  public void touchLastMessage(String preview, LocalDateTime at) {   // 목록 정렬용 비정규화
    this.lastMessage = preview;
    this.lastMessageAt = at;
  }

  public void close() { this.status = ChatRoomStatus.CLOSED; }
}
```

### ChatMessage (`domain/chat/entity/ChatMessage.java`, `@Table("chatroom_messages")`)

불변 append-only. `messageType`(TEXT/IMAGE/FILE/SYSTEM)로 종류를 구분한다. **content는 nullable**(첨부만 있는
메시지·SYSTEM 안내), **sender_id도 nullable**(SYSTEM 메시지는 발신자 없음). 첨부는 private S3 object key만
`attachments`(jsonb 배열)에 담고 조회 시 presigned GET URL로 변환한다(엔티티는 URL을 모른다). 생성은 정적 팩터리로만.

```java
@Entity
@Table(name = "chatroom_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ChatMessage {

  @Id @GeneratedValue private UUID id;
  @Column(name = "room_id", nullable = false) private UUID roomId;
  @Column(name = "sender_id") private UUID senderId;                 // nullable — SYSTEM은 발신자 없음

  @Enumerated(EnumType.STRING)
  @Column(name = "message_type", nullable = false, length = 20) private ChatMessageType messageType;

  @Column(name = "content") private String content;                 // nullable — 첨부만/SYSTEM

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attachments", columnDefinition = "jsonb") private List<ChatAttachment> attachments;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

  // 정적 팩터리: 불변식은 여기서 — 빈 content면 CHAT_MESSAGE_EMPTY, 첨부 0개면 CHAT_ATTACHMENT_EMPTY
  public static ChatMessage text(UUID roomId, UUID senderId, String content) { ... }
  public static ChatMessage attachment(UUID roomId, UUID senderId, ChatMessageType type,
      List<ChatAttachment> attachments, String caption) { ... }
  public static ChatMessage system(UUID roomId, String content) { ... }   // senderId = null
}
```

### ChatAttachment (VO, `domain/chat/entity/ChatAttachment.java`)

식별자 없는 불변 값이라 `record`로 캡슐화한다. jsonb 배열 요소로 저장된다(Hibernate JSON 매퍼 키는 camelCase).

```java
public record ChatAttachment(String attachmentKey, String name, String contentType, Long size) {}
```

## 읽음 처리

`PATCH /chats/{id}/read` → `ChatReadService.read()`가 참여자 인가 후 `room.markRead(me, now())`로 **내 커서만**
현재 시각으로 올린다. 이후 목록의 `unread_count`(상대 커서 이후 메시지 수)가 0이 된다.

## QueryDSL 커서 기반 페이지네이션

채팅 이력 조회는 오프셋이 아닌 **(createdAt, id) 튜플 커서**를 QueryDSL로 뽑는다(파생쿼리 아님). 동시각(같은
createdAt) tie는 id로 안정 정렬한다. 정렬은 `created_at DESC, id DESC`, 인덱스는 V24의
`(room_id, created_at DESC, id DESC)`.

```java
// ChatMessageRepositoryImpl (RepositoryCustom 프래그먼트)
@Override
public List<ChatMessage> findByCursor(UUID roomId, LocalDateTime cursorCreatedAt, UUID cursorId, int limit) {
  QChatMessage message = QChatMessage.chatMessage;
  BooleanBuilder where = new BooleanBuilder();
  where.and(message.roomId.eq(roomId));
  if (cursorCreatedAt != null && cursorId != null) {
    where.and(message.createdAt.lt(cursorCreatedAt)
        .or(message.createdAt.eq(cursorCreatedAt).and(message.id.lt(cursorId))));
  }
  return queryFactory.selectFrom(message)
      .where(where)
      .orderBy(message.createdAt.desc(), message.id.desc())
      .limit(limit)
      .fetch();
}
```

- 서비스는 `limit = size + 1`로 조회해 `hasNext`를 판정하고, 마지막 행을 **opaque 커서**(Base64 `epochSecond_nano_id`)로 인코딩해 내려준다. 응답은 `{ messages, next_cursor, has_next }`(snake_case).

```json
{ "messages": [...], "next_cursor": "MTcy..._id", "has_next": true }
```

## 실시간 전달 / 오프라인 알림

실시간 전달은 위 **Redis pub/sub → STOMP relay**가 담당한다. 현재 채팅 전송 경로에는 온라인 세션 감지 후
FCM으로 분기하는 **오프라인 푸시가 배선돼 있지 않다** — `infra/fcm/FcmConfig`만 존재하고, 검수 완료 등 다른
도메인 알림 용도다. 채팅 오프라인 FCM 푸시를 추가한다면 relay(subscriber) 이후 단계로 붙이며, 실패는 로그만
남겨 실시간 전달을 막지 않는다(설계 문서에 명시된 경우에만 구현).

## Flyway 마이그레이션 (반영 이력)

- **V24 `chat_read_and_attachment`**: `chatroom`에 `report_review_id`·`user_last_read_at`·`adjuster_last_read_at`·`last_message_at` 추가, `chatroom_messages`에 `message_type`·(구)단수 첨부 컬럼 추가 + `sender_id` NOT NULL 완화(SYSTEM), 커서·목록 인덱스 생성.
- **V25 `chat_message_attachments_array`**: 단수 첨부 컬럼 → `attachments`(jsonb 배열)로 전환·이관 후 단수 컬럼 제거.

새 마이그레이션은 `V{n}__{description}.sql` 네이밍을 따른다.

## 구현 체크리스트
- [ ] `build.gradle`에 `spring-boot-starter-websocket`(+ redis) 추가
- [ ] `WebSocketConfig` — 엔드포인트 `/ws-chat`, SimpleBroker `/topic` 단일(구독 전용), 오리진은 앱 CORS 패턴, 3개 Chat* 컴포넌트 주입
- [ ] `ChatHandshakeInterceptor` — `access_token` 쿠키 읽어 `jwtProvider.validate` + userId attribute(`chatUserId`)
- [ ] `ChatHandshakeHandler` — attribute → Principal(name=userId) 승격
- [ ] `ChatSubscribeInterceptor` — SUBSCRIBE 시 `/topic/chat.rooms.{roomId}` 참여자 인가(`room.isMember`)
- [ ] `ChatRoom` / `ChatMessage` / `ChatAttachment` — 2자 멤버십·읽음 커서·jsonb 첨부·메시지 타입을 설계 DB 스키마와 일치
- [ ] Flyway SQL: 신규 스키마는 `V{n}__...sql`
- [ ] `ChatMessageController` (REST) — `POST /chats/{id}/messages` 전송(@MessageMapping 아님), `GET .../messages` 커서 조회
- [ ] `ChatMessageCommandService` — 저장 + `touchLastMessage` + `chatEventPublisher.publishAfterCommit`
- [ ] `ChatEventPublisher` / `ChatMessageSubscriber` / `RedisConfig` — afterCommit 발행 + 채널 구독 relay(`/topic/chat.rooms.{roomId}`)
- [ ] `ChatMessageRepositoryImpl` — QueryDSL (createdAt, id) 커서
- [ ] `ChatReadService` — `markRead` 내 커서 갱신
