# 구현 요청: WebSocket(STOMP) 채팅 기능

## 요청 내용

Spring Boot 백엔드에 실시간 채팅 기능을 구현한다.

## 구현 범위

### 엔티티
- `ChatRoom` — 채팅방 (UUID PK, 제목, 타입, 참여자 목록)
- `ChatMessage` — 채팅 메시지 (UUID PK, 채팅방 ID, 발신자 ID, 내용, 전송 시각)
- `ChatRoomMember` — 채팅방 멤버십 (채팅방 + 유저 연관)

### REST API
- `POST /api/chat/rooms` — 채팅방 생성
- `GET /api/chat/rooms/{roomId}` — 채팅방 조회
- `GET /api/chat/rooms` — 내 채팅방 목록
- `POST /api/chat/rooms/{roomId}/members` — 채팅방 입장
- `DELETE /api/chat/rooms/{roomId}/members/me` — 채팅방 퇴장
- `GET /api/chat/rooms/{roomId}/messages` — 채팅 이력 (커서 기반 페이지네이션)

### WebSocket(STOMP)
- 연결 엔드포인트: `/ws`
- 발행: `/app/chat.send` — 메시지 전송
- 구독: `/topic/chat/{roomId}` — 실시간 메시지 수신
- 입장/퇴장 이벤트: `/app/chat.join`, `/app/chat.leave`

### FCM 오프라인 푸시
- 수신자가 WebSocket 미연결 상태(오프라인)이면 FCM 알림 발송
- 기존 infra/fcm 인프라 활용

### 추가 사항
- Flyway 마이그레이션 SQL 포함
- 패키지: `com.soma.backend.domain.chat`
- `spring-boot-starter-websocket` 의존성은 build.gradle에 이미 추가됨
- JWT 인증된 사용자만 채팅방 접근 가능 (HandshakeInterceptor에서 토큰 검증)

## 기술 스택
- Spring Boot 4.0.6 / Java 21
- WebSocket + STOMP (spring-boot-starter-websocket)
- JPA + PostgreSQL (pgvector:pg16)
- Flyway (ddl-auto=validate)
- UUID PK, snake_case JSON, flat response

## 제약 사항
- flat JSON 응답 (래퍼 없음), 필드명 snake_case
- 엔티티 PK는 UUID
- 예외는 `BusinessException(ErrorCode)` + `GlobalExceptionHandler`
- `open-in-view: false` — 서비스 레이어 내 트랜잭션 완료
