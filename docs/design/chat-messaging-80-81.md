# 설계서 — backend#80·#81 채팅방 목록·상담 수락/거절 + 1:1 메시지(첨부·시스템 메시지·STOMP·Redis relay)

> 상위 스토리 #79 · 이슈 #80(목록·수락), #81(메시지 조회·전송·STOMP)
> 도메인: `domain/chat` (그린필드 — 빈 패키지 + V1 스키마 + Chat ErrorCode만 존재)
> 브랜치: `feat/80-chat-messaging` (develop 기준 분기). QueryDSL 배선은 **PR #110로 develop에 선반영 완료**.
> 기준: **develop**(80f6844) 실제 소스.

---

## 0. 확정 결정 (2026-07-16)

1. **상담 수락 = chat 도메인 소유.** `ChatConsultationCommandService.accept`가 report 엔티티를 직접 호출: 내 `ReportReview.accept()`(→ACCEPTED) + 형제 제안 `reject()`(→REJECTED) + `Report.accept(adjusterId)`(COUNSELING→CLOSED) + SYSTEM 메시지. **내 채팅방·형제 방 모두 CLOSED**(상담 종료). 엔드포인트 `PATCH /chats/{chatRoomId}/accept`.
2. **상담 거절 = chat 도메인.** `PATCH /chats/{chatRoomId}/reject`. 내 `ReportReview.reject()`(→REJECTED) + report **COUNSELING→AWAITING_ADOPTION**(전이표에 우리가 추가) + `chatroom.close()`(→CLOSED) + SYSTEM 메시지. 다른 제안은 유지.
3. **상태 분리 (핵심).** `chatroom.status`는 **방 생명주기 `ACTIVE`/`CLOSED`만**(양 경로 공통). **수락/거절 결정은 `report_reviews.status`**(SENT/COUNSELING/ACCEPTED/REJECTED, 이미 존재)에 둔다. 결정 상태를 chatroom에 중복시키지 않는다.
4. **안읽음 = `chatroom` 읽음 커서 2컬럼**(별도 테이블 없음).
5. **실시간 = Redis pub/sub relay** + STOMP(쿠키 핸드셰이크). 다중 서버 팬아웃.
6. **첨부 = 백엔드 업로드(별도 엔드포인트).** `POST /chats/{id}/attachments`(multipart) → 검증(pdf/jpg/png·용량) → **private S3** → **object key** 반환/저장. 조회는 **단기 presigned GET URL**. (진단서·증권 등 민감 문서)
7. **공유 리포트 상세(사용자용) 조회 API는 report 팀이 추가.** 채팅은 `report_review_id`만 노출.

## 0-1. 주체 정정

수락/거절 주체는 **사용자(리포트 소유자)** (UI 헤더가 상대 사정사 표기 = 뷰어는 사용자, 기존 `decide`도 소유자 인가) → 채팅방 `user_id == me`로 인가.

## 0-2. develop 재조사 결과

- Flyway 최신 = V14 → 우리 마이그레이션은 **V15**.
- `Report.accept(adjusterId)`(COUNSELING→CLOSED), `ReportReview.accept()/reject()`, `ReportCommandService.decide`, `PATCH /reports/{reportId}/proposals/{proposalId}` 이미 존재. 수락은 이 **엔티티 메서드**를 chat 서비스가 재사용(서비스 `decide`는 호출 안 함).
- 전이표에 `COUNSELING→AWAITING_ADOPTION` 없음(`Report.java` `ALLOWED_TRANSITIONS`) → 거절 위해 우리가 추가.
- S3 업로드 코드 없음 → `infra/s3`에 업로더 신설(putObject + presigned GET).
- 커서 페이지네이션 유틸 없음 → 신규.
- 사정사 검색 API 없음 → 검색 경로 방 생성은 우리 범위 밖.

## 0-3. 채팅 진입 경로 2종 (report_review 유무)

`report_review`는 **사정사가 검수할 때** 생성된다. 진입 경로에 따라 갈린다:

| 경로 | 흐름 | report_id / report_review_id | chatroom.status | review_status | 공유 리포트 | 수락/거절 |
|------|------|------------------------------|-----------------|---------------|------------|-----------|
| **파이프라인** | 입력 → AI 리포트 → 사정사 검수 → 사용자가 사정사 선택 → 방 생성 | 둘 다 set | ACTIVE/CLOSED | report_reviews.status | ✅ | ✅ |
| **사정사 검색** | 사용자가 사정사 검색 → 방 생성(리포트·검수 없음) | 둘 다 **null** | ACTIVE/CLOSED | **null** | ❌ | ❌ |

→ `report_id`·`report_review_id`는 **nullable**. 공유 리포트·수락·거절은 `report_review_id != null`(파이프라인)일 때만. 검색 방은 순수 메시징(항상 ACTIVE, 결정 없음).

## 0-4. 범위

- **In**: GET /chats, GET/POST /chats/{id}/messages, POST /chats/{id}/attachments, POST /chats/{id}/read, PATCH /chats/{id}/accept|reject, WebSocket/STOMP + 쿠키 핸드셰이크, Redis relay, V15.
- **Out**: 채팅방 생성(다른 팀), FCM 푸시, 공유 리포트 상세 조회(report 팀), 사정사 검색.

---

## 1. 의존성·조율

| # | 항목 | 소유 | 상태 |
|---|------|------|------|
| D1 | 채팅방 생성(사정사 선택 시 chatroom INSERT, **report_id·report_review_id 세팅 + status='ACTIVE'**). develop에 없음 → 테스트 시드 필요 | 다른 팀 | 조율 |
| D2 | `chatroom.status` = `ACTIVE`/`CLOSED`(생성 시 ACTIVE). 결정은 report_reviews.status | 생성 측과 합의 | 조율 |
| D3 | `Report` 전이표에 `COUNSELING→AWAITING_ADOPTION` 추가 — 우리가 수정(승인 완료) | 우리 | 진행 |
| D5 | 공유 리포트(사용자용) 상세 조회 API 신설 | report 팀 | 조율 요청 |
| D6 | 수락 로직 chat 이동 → report `decide`(/reports/proposals) 채택 경로와 이원화. 목록 화면 decide 유지/정리 | report 팀 | 조율 |

---

## 2. 도메인 모델

### 2.1 Aggregate

| Root | 테이블 | 불변식 |
|------|--------|--------|
| **ChatRoom** | `chatroom` | 참여자 user_id·adjuster_id 2인 고정(1:1). `report_review_id`(nullable)로 상담 대상 제안(공유 리포트) 식별 — 검색 방은 null. `status`는 생명주기 `ACTIVE→CLOSED`만(결정 상태 아님). 읽음 커서는 본인 것만 |
| **ChatMessage** | `chatroom_messages` | 불변(append-only). sender는 참여자. TEXT는 content 필수, 첨부는 attachment_key 필수, SYSTEM은 서버 생성 |

수락/거절은 chat 서비스가 `ReportRepository`·`ReportReviewRepository`를 주입해 **크로스-도메인 쓰기**(불변식은 report 엔티티가 방어).

### 2.2 enum (VO)

- `ChatRoomStatus`: **`ACTIVE`, `CLOSED`** (방 생명주기).
- `ChatMessageType`: `TEXT`, `IMAGE`, `FILE`, `SYSTEM`.
- 결정 상태 재사용: `ReviewStatus`(report_reviews), `ReportStatus`(reports).

### 2.3 리치 메서드

- `ChatRoom.markRead(readerId, at)` / `touchLastMessage(preview, at)` / `close()` / `isMember(id)` / `counterpartOf(me)`.
- `ChatMessage.text(...)` / `attachment(roomId, senderId, type, key, name, contentType, caption)` / `system(roomId, text)`.
- (report) `Report` 전이표에 `COUNSELING→AWAITING_ADOPTION` 추가. `ReportReview.accept()/reject()`·`Report.accept()`는 기존 재사용.

---

## 3. DB 스키마 — `V15__chat_read_and_attachment.sql`

`chatroom`/`chatroom_messages`는 V1에 존재. `ddl-auto: validate`라 매핑 컬럼을 V15로 추가. (`status` varchar(20)는 이미 존재 — 값만 ACTIVE/CLOSED로 사용, 생성 측이 'ACTIVE' 세팅)

```sql
-- 상담 대상 제안 + 읽음 커서 + 목록 정렬 + 감사 컬럼
ALTER TABLE chatroom
  ADD COLUMN report_review_id      uuid REFERENCES report_reviews (id),  -- 제안(공유 리포트) 참조. nullable(파이프라인만 set). ERD 정합
  ADD COLUMN user_last_read_at     timestamp,
  ADD COLUMN adjuster_last_read_at timestamp,
  ADD COLUMN last_message_at       timestamp,
  ADD COLUMN updated_at            timestamp;

-- 메시지: 타입 + 첨부(private S3 객체 key)
ALTER TABLE chatroom_messages
  ADD COLUMN message_type            varchar(20) NOT NULL DEFAULT 'TEXT',
  ADD COLUMN attachment_key          text,            -- private S3 key (공개 URL 아님)
  ADD COLUMN attachment_name         varchar(255),
  ADD COLUMN attachment_content_type varchar(100),
  ADD COLUMN attachment_size         bigint;
-- content는 V1에서 이미 nullable(첨부 전용 메시지 허용)

CREATE INDEX idx_chatroom_last_message_at ON chatroom (last_message_at DESC);
CREATE INDEX idx_chatroom_messages_room_created
  ON chatroom_messages (room_id, created_at DESC, id DESC);   -- 커서 + 안읽음 COUNT
```

- `ChatRoom extends BaseEntity`(created_at/updated_at) → `updated_at` 추가로 validate 통과. `ChatMessage`는 불변이라 BaseEntity 미상속, `created_at`만 매핑.
- 첨부는 컬럼 방식(메시지당 1첨부). `attachment_key`는 private S3 key만 저장, 조회 시 presigned GET URL로 변환.

---

## 4. API 계약 (7종)

공통: 성공 `{status,message,data}`, 실패 `{status,code,message}`, 필드 **snake_case**. 인증은 `access_token` 쿠키(HttpOnly). 컨트롤러 `ResponseEntity<ApiResponse<T>>`.

### ① GET `/chats` — 내 채팅방 목록
- data: `{ rooms: [ { chat_room_id, report_id, report_review_id, status, review_status, case_no, accident_type, counterpart:{user_id,name,avatar_url}, last_message, last_message_at, unread_count } ] }`
  - `status` = chatroom 생명주기(ACTIVE/CLOSED). `review_status` = report_reviews.status(SENT/COUNSELING/ACCEPTED/REJECTED) — 파이프라인 방만, 검색 방은 report_id·report_review_id·review_status 모두 **null**.
  - `case_no`·`accident_type` = 연결 리포트(reports) LEFT JOIN — 검색 방은 **null**. `accident_type`은 enum 값(예 `traffic`). `counterpart.avatar_url` = 상대방 users.avatar_url(미설정 시 null). (FE 요청 카드 #48/PR #53 반영)
- 정렬 `last_message_at DESC NULLS LAST`. `unread_count` = 상관 서브쿼리(상대가 보낸 메시지 중 내 last_read_at 이후 COUNT).
- 200 / 401.

### ② GET `/chats/{chatRoomId}/messages?cursor=&size=` — 커서 이력
- Query `cursor`(opaque, 첫 페이지 생략), `size`(기본 30, 최대 100).
- data: `{ messages:[ { message_id, sender_id, message_type, content, attachment:{url,name,content_type}|null, is_mine, created_at } ], next_cursor, has_next }` (최신순)
- 커서 = base64(`{epochSecond}_{nano}_{messageId}`)(밀리초 절삭 없이 나노초 정밀도 보존), 정렬 `created_at DESC, id DESC`.
- 첨부 `attachment.url`은 조회 시 생성하는 **단기 presigned GET URL**.
- 200 / 401 / 403(비참여자) / 404.

### ③ POST `/chats/{chatRoomId}/messages` — 전송(텍스트/첨부)
- body: `{ content?, attachment?:{ attachment_key, name, content_type } }`(⑦ 응답 메타) — 최소 1개. 첨부+캡션 가능.
- `message_type` 서버 파생: content_type 이미지 → IMAGE, 그 외 첨부 → FILE, 없으면 TEXT.
- 저장 → `last_message/last_message_at` 갱신 → **afterCommit** Redis publish → STOMP `/topic/chat.rooms.{id}`.
- data: `{ message_id, chat_room_id, sender_id, message_type, content, attachment:{url,name,content_type}|null, created_at }` (url은 presigned)
- 201 / 400(둘 다 없음) / 401 / 403 / 404 / 409(CLOSED 방).

### ④ PATCH `/chats/{chatRoomId}/accept` — 상담 수락(사용자)
- 주체=소유자(user_id==me). `report_review_id` 있는 파이프라인 방만. 방 ACTIVE·report COUNSELING 전제.
- 동작(단일 트랜잭션): 내 제안(`report_review_id`) `ReportReview.accept()` + 형제 제안(같은 report_id) `reject()` + `Report.accept()`(COUNSELING→CLOSED) + SYSTEM 메시지. **내 방·형제 방 모두 `close()`** — 형제 방에도 "다른 사정사 선택" 종료 SYSTEM 메시지를 각 방 토픽으로 브로드캐스트.
- data: `{ chat_room_id, chat_room_status:"CLOSED", review_status:"ACCEPTED", report_id, report_status:"CLOSED" }`
- 200 / 401 / 403(소유자 아님) / 404 / 409(이미 결정/COUNSELING 아님, 또는 report_review_id 없는 검색 방).

### ⑤ PATCH `/chats/{chatRoomId}/reject` — 상담 거절(사용자) **[추가]**
- 주체·전제 ④와 동일.
- 동작(단일 트랜잭션): 내 제안(`report_review_id`) `ReportReview.reject()` + report **COUNSELING→AWAITING_ADOPTION**(D3) + `chatroom.close()`(→CLOSED) + SYSTEM 메시지. 다른 제안은 유지.
- data: `{ chat_room_id, chat_room_status:"CLOSED", review_status:"REJECTED", report_id, report_status:"AWAITING_ADOPTION" }`
- 200 / 401 / 403 / 404 / 409(이미 결정, 또는 report_review_id 없는 검색 방).

### ⑥ POST `/chats/{chatRoomId}/read` — 읽음 처리 **[추가]**
- 주체=참여자. body 없음. `chatroom.markRead(me, now)` → 이후 ①의 `unread_count`=0.
- data: `{ chat_room_id, read_at }`. 200 / 401 / 403 / 404.

### ⑦ POST `/chats/{chatRoomId}/attachments` — 첨부 업로드 **[추가]**
- 주체=참여자. `multipart/form-data`의 `file`. 검증(화이트리스트 pdf/jpg/png·용량 + **매직바이트 시그니처가 선언 MIME과 일치**해야 함 — 위장 업로드 차단) → private S3(key `chat/{roomId}/{uuid}_{name}`).
- data: `{ attachment_key, name, content_type, size }` → ③의 `attachment`로 전달.
- 201 / 400(형식·용량) / 401 / 403 / 404. `infra/s3` 업로더 신설. 조회는 ②가 presigned GET.

> **공유 리포트 열기**: `report_review_id`(=proposal_id)로 프론트가 report 팀의 사용자용 제안 상세 조회 API(D5)를 연다. 내용(예상 보상 범위·쟁점·보장·특약·근거)은 REPORT_REVIEWS에 있음. `report_review_id`가 null(검색 방)이면 버튼 숨김.

---

## 5. WebSocket(STOMP) + Redis relay

- 엔드포인트 `/ws-chat`(native WebSocket). 브로커 `enableSimpleBroker("/topic")`, prefix `/app`.
- 구독 `/topic/chat.rooms.{chatRoomId}`. 클라는 구독만, 전송은 REST(③) → `@MessageMapping` 불필요.
- 핸드셰이크: `HandshakeInterceptor`에서 `CookieProvider.readCookie(req, ACCESS_TOKEN_COOKIE)` → `JwtProvider.validate/getUserId` → Principal. 실패 시 거부(401 `CHAT_WS_UNAUTHORIZED`).
- 구독 인가: `ChannelInterceptor`(SUBSCRIBE)에서 목적지 roomId 참여자 검증.
- Redis relay: ③ 커밋 후 `RedisTemplate.convertAndSend("chat.message", json)` → 전 인스턴스 `RedisMessageListenerContainer` 수신 → `SimpMessagingTemplate.convertAndSend("/topic/chat.rooms."+roomId, dto)`. 전송 서버도 relay 경유로만(각 세션 1회). 영속성은 DB.

---

## 6. 트랜잭션·인가

- 조회(①②) `readOnly`. 전송(③) `@Transactional`(저장+last_message), publish는 afterCommit. 업로드(⑦) 트랜잭션 밖(S3 I/O). 읽음(⑥) 단일 방. 수락/거절(④⑤) 단일 `@Transactional`(chat+report_reviews+report).
- 인가: `principal==null`→401. ①②③⑥⑦은 `room.isMember(me)` 아니면 403. ④⑤는 `room.user_id==me` 아니면 403.
- `open-in-view:false`.

---

## 7. 패키지 구조

```
domain/chat
├── controller/  ChatRoomController(①④⑤⑥) · ChatMessageController(②③⑦)
├── dto/         ChatRoomSummaryResponse · ChatMessageResponse · ChatMessageListResponse
│                SendMessageRequest · UploadAttachmentResponse · ConsultationDecisionResponse · ReadResponse
├── entity/      ChatRoom(BaseEntity) · ChatMessage · ChatRoomStatus(ACTIVE/CLOSED) · ChatMessageType
├── repository/  ChatRoomRepository(+Custom/Impl: 목록 unread+review_status 조인 QueryDSL)
│                ChatMessageRepository(+Custom/Impl: 커서 페이지네이션 QueryDSL)
└── service/     ChatRoomQueryService · ChatMessageQueryService · ChatMessageCommandService(afterCommit publish)
                 ChatReadService · ChatAttachmentService(⑦) · ChatConsultationCommandService(④⑤, report repos 주입)

global/config/WebSocketConfig · global/security/{ChatHandshakeInterceptor,ChatHandshakeHandler,ChatSubscribeInterceptor}
infra/redis/{RedisConfig(+listener container), ChatEventPublisher, ChatMessageSubscriber, dto/ChatBroadcastMessage}
infra/s3/ChatAttachmentUploader (putObject + presigned GET URL 발급)
report(조율): Report 전이표에 COUNSELING→AWAITING_ADOPTION 추가
resources/db/migration/V15__chat_read_and_attachment.sql
```

QueryDSL은 develop 배선됨(PR #110). `*RepositoryCustom`/`*RepositoryImpl` + `JPAQueryFactory`. 목록은 `chatroom` LEFT JOIN `report_reviews`(review_status).

---

## 8. 테스트·경계 케이스

- 목록: unread(커서 null=전부 안읽음, 내 메시지 제외), 정렬, review_status(파이프라인만·검색 null), status ACTIVE/CLOSED.
- 이력 커서: 첫/중간/마지막, tie→id, 비참여자 403, 첨부 presigned.
- 전송: 둘 다 없음 400, 첨부만/캡션+첨부, key roomId 접두 검증, 롤백 시 미발행, CLOSED 방 409.
- 업로드: 화이트리스트 밖/용량 400, private 저장, 비참여자 403.
- 읽음: 내 커서만, 이후 unread=0.
- 수락: 소유자 아님 403, 검색 방(report_review_id null) 409, review ACCEPTED·형제 REJECTED·report CLOSED·내 방·형제 방 CLOSED 원자성.
- 거절: `COUNSELING→AWAITING_ADOPTION`(D3), review REJECTED, 방 CLOSED, 타 제안 유지.
- WS: 쿠키 없는 핸드셰이크 거부, 비참여자 구독 거부, 로컬 2인스턴스 relay.
- `@SpringBootTest` + 실제 test_db, MockMvc.

---

## 9. 열린 항목

1. **D6** 수락 로직 chat 이동 → report `decide` 채택 경로 이원화. 목록 화면 decide 유지/정리.
2. **D2** `chatroom.status`(ACTIVE/CLOSED)·생성 측 정합.
3. **D1** 채팅방 생성 주체(테스트 시드).
4. **D5** 공유 리포트 사용자용 조회 API — report 팀 신설.
5. 첨부 허용 MIME·용량 상한, presigned URL 만료(예: 5분).
6. 수락·거절 모두 방 CLOSED — 종료 후 읽기 전용/숨김 처리(프론트) 확정.
```

