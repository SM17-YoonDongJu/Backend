# realtime-developer 구현 요약

## 구현된 파일

- `src/main/java/com/soma/backend/domain/chat/config/ChatWebSocketConfig.java`
- `src/main/java/com/soma/backend/domain/chat/config/WebSocketEventListener.java`
- `src/main/java/com/soma/backend/domain/chat/service/PresenceService.java`
- `src/main/java/com/soma/backend/domain/chat/controller/ChatStompController.java`
- `src/main/java/com/soma/backend/domain/chat/dto/ChatSendRequest.java`
- `src/main/java/com/soma/backend/domain/chat/dto/ChatRoomActionRequest.java`
- `src/main/java/com/soma/backend/infra/fcm/FcmConfig.java`
- `src/main/java/com/soma/backend/infra/fcm/FcmService.java`

## 수정된 파일
- `domain/chat/dto/MessageResponse.java` — `systemMessage()` 정적 팩토리 추가

## WebSocket 설정
- 엔드포인트: `/ws` (JwtHandshakeInterceptor)
- App prefix: `/app`, 브로커: `/topic`, `/user`
- 발행: `/app/chat.send`, `/app/chat.join`, `/app/chat.leave`
- 구독: `/topic/chat/{roomId}`
- sender_id는 Principal(CustomUserDetails)에서 추출 (위변조 방지)

## FCM 구현 현황
- `FcmConfig`: `@ConditionalOnProperty(name="fcm.service-account-path")` — 프로퍼티 없으면 Bean 미생성
- `FcmService`: FirebaseApp 없으면 stub (debug 로그만). 실제 send는 TODO — users/device 테이블 완성 후 활성화 필요
- 오프라인 FCM 실패는 try/catch로 격리 (채팅 트랜잭션과 분리)

## 미구현 항목
- FCM 실제 전송: users/device 테이블 완성 후 활성화
- SUBSCRIBE 멤버십 인가: security-developer StompAuthChannelInterceptor에서 처리 필요
- withSockJS(): 선택사항, 미적용

## 빌드 검증
- `./gradlew compileJava` → BUILD SUCCESSFUL
