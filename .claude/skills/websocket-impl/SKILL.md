---
name: websocket-impl
description: "Spring Boot WebSocket(STOMP) 채팅 구현 가이드. WebSocketConfig, 메시지 핸들러, ChatRoom·ChatMessage 엔티티, 커서 페이지네이션, JWT 핸드셰이크 인증, FCM 오프라인 푸시 패턴 정의. realtime-developer 에이전트가 참조."
---

# WebSocket(STOMP) 구현 가이드

이 프로젝트의 WebSocket 채팅 구현 패턴을 정의한다.

## 의존성

```gradle
implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

## WebSocketConfig 기본 구조

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .addInterceptors(new JwtHandshakeInterceptor(jwtProvider));
    }
}
```

## JWT 핸드셰이크 인증

WebSocket 연결 시 쿼리 파라미터로 JWT를 전달받아 검증한다.

```java
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        URI uri = request.getURI();
        String query = uri.getQuery(); // token=<jwt>
        if (query != null && query.startsWith("token=")) {
            String token = query.substring(6);
            if (jwtProvider.validateToken(token)) {
                UUID userId = jwtProvider.getUserId(token);
                attributes.put("userId", userId);
                return true;
            }
        }
        return false; // 인증 실패 → 연결 거부
    }
}
```

## 메시지 핸들러

```java
@Controller
public class ChatController {

    @MessageMapping("/chat.send")
    @SendTo("/topic/chat/{roomId}")
    public ChatMessageResponse sendMessage(
            @DestinationVariable UUID roomId,
            @Payload ChatMessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID senderId = (UUID) headerAccessor.getSessionAttributes().get("userId");
        // 채팅방 멤버십 검증 필수
        chatService.validateMembership(roomId, senderId);
        ChatMessage saved = chatService.save(roomId, senderId, request.content());
        // 수신자 오프라인 시 FCM 발송
        fcmService.sendIfOffline(roomId, senderId, saved);
        return ChatMessageResponse.from(saved);
    }
}
```

## 엔티티 패턴

PK는 UUID, 공통 컨벤션(`snake_case`, Flyway 마이그레이션) 준수.

ERD 기준 테이블명: `CHATROOM`, `CHATROOM_MESSAGES`. `ChatRoomMember` 별도 엔티티 없음 — 참여자는 `participants uuid[]` 배열로 관리.

```java
@Entity
@Table(name = "chatroom")
public class ChatRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ERD: participants uuid[] — PostgreSQL 배열 타입
    @Column(columnDefinition = "uuid[]")
    private UUID[] participants;

    private String lastMessage;

    private LocalDateTime createdAt;
}

@Entity
@Table(name = "chatroom_messages")
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID roomId;

    @Column(nullable = false)
    private UUID senderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private LocalDateTime createdAt;
}
```

## 커서 기반 페이지네이션

채팅 이력 조회는 오프셋 방식이 아닌 커서(createdAt + id) 방식을 사용한다.

```java
// Repository
List<ChatMessage> findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(
    UUID roomId, LocalDateTime cursor, Pageable pageable);

// 응답 예시
{
  "messages": [...],
  "next_cursor": "2026-06-10T12:00:00Z",
  "has_next": true
}
```

## FCM 오프라인 푸시 패턴

```java
// 연결된 세션 수 확인 후 오프라인 판단
public void sendIfOffline(UUID roomId, UUID senderId, ChatMessage message) {
    SimpUserRegistry userRegistry = ...; // 주입
    Set<SimpSession> sessions = userRegistry.getUser(recipientId.toString()).getSessions();
    if (sessions == null || sessions.isEmpty()) {
        fcmService.send(recipientDeviceToken, message); // 비동기 @Async
    }
}
```

## Flyway 마이그레이션 네이밍

```
V{n}__create_chat_tables.sql
V{n+1}__add_chat_room_member_table.sql
```

## 구현 체크리스트
- [ ] `build.gradle`에 `spring-boot-starter-websocket` 추가
- [ ] `WebSocketConfig` — STOMP 브로커, `/ws` 엔드포인트, JWT HandshakeInterceptor
- [ ] `JwtHandshakeInterceptor` — 쿼리 파라미터 `token=` 추출 및 검증
- [ ] `ChatRoom` 엔티티 → `CHATROOM` (participants uuid[], lastMessage) / `ChatMessage` → `CHATROOM_MESSAGES`. `ChatRoomMember` 엔티티 없음.
- [ ] Flyway SQL: `V{n}__create_chat_tables.sql`
- [ ] `ChatController` (STOMP) — `@MessageMapping("/chat.send")`, participants 배열로 멤버십 검증, lastMessage 갱신
- [ ] `ChatRoomController` (REST) — 생성·조회
- [ ] `ChatMessageController` (REST) — 커서 페이지네이션
- [ ] FCM 오프라인 푸시 — `@Async`, 실패 시 로그만 기록
