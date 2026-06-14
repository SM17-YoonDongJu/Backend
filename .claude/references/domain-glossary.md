# Domain Glossary — 손해사정 도메인

> **출처:** Notion — API 명세서(개별 페이지 20개) + 기능리스트(개별 페이지 20개)  
> **규칙:** 이 파일의 모든 항목은 위 Notion 페이지 출처로만 작성한다. 임의 해석·추측 금지.  
> **최종 동기화:** 2026-06-14

---

## 1. 서비스 개요

보험금 지급 결과에 의문이 있는 사용자를 대상으로:
1. **AI 리포트** — 적용 가능 보장·누락 특약·분쟁 포인트 분석 (FastAPI 담당)
2. **손해사정사 연결** — AI 초안을 검수한 사정사 중 사용자가 직접 선택해 매칭 (Spring Boot 담당)

> 보험업법 §189: 협상·합의 대리는 제공하지 않는다. 정보 제공 및 손해사정사 연결만 수행.

---

## 2. 핵심 행위자 (Role)

| 역할 | ERD Enum 값 | API `userType` 값 | 설명 | 접근 가능 기능 |
|------|------------|------------------|------|--------------|
| 일반 사용자 | `USER` | `insured_person` | 보험금 검토가 필요한 보험 계약자 | 리포트 생성 요청, 검수 리포트 목록 조회, 매칭 요청 |
| 자격 사정사 | `CERTIFICATED_ADJUSTER` | `adjuster` | 금융위원회 등록 손해사정사, 운영팀 활성화 완료 | 케이스 채택, 검수·등록, 심층 분석 리포트 열람 |
| 미자격 사정사 | `UNCERTIFICATED_ADJUSTER` | `adjuster` | 신청 후 미활성화 상태 | 로그인 가능, 채택 API 호출 시 403 |
| 관리자 | `ADMIN` | — | 운영팀 | 전체 관리, 사정사 계정 활성화, 구독 관리 |

> **`userType` vs Role 구분:** 회원가입 API(`POST /auth/register`) 요청 시 `userType`으로 `insured_person` 또는 `adjuster`를 전달한다. 이 값이 서버 내부 Role(`USER` / `CERTIFICATED_ADJUSTER` 등) 매핑의 출발점이다.  
> 보험업법 §186: 금융위 등록 자격자만 활성화 허용. 미활성화 사정사는 로그인은 되나 케이스 채택 불가(403).

---

## 3. 리포트 상태머신 (REPORTS.status)

```
[AI 초안 생성 완료]
        ↓
AWAITING_INSPECTION  ← 검수 대기. 사정사 채택 가능 목록에 노출됨.
        ↓  (1건 이상 검수 리포트 등록 완료)
AWAITING_ADOPTION    ← 사용자 선택 대기. 복수 검수 리포트 비교 가능.
        ↓  (사용자가 사정사 선택 → ChatRoom 즉시 생성)
COUNSELING           ← 채팅 중. WebSocket 채널 개설됨. 거절 없음.
        ↓  (사정사가 최종 리포트를 REPORTS 테이블에 등록)
MATCHED              ← 사정사 최종 리포트 확정. AI 리포트와 사정사 리포트를
                       같은 REPORTS 테이블에서 구분하는 enum 값.
```

### 상태별 사용자 표시 문자열 (API 응답 `status` 필드)

| DB/도메인 코드 | 사용자 표시 문자열 | 사정사 채택 가능 | 사용자 리포트 열람 | 매칭 요청 가능 |
|--------------|-----------------|----------------|------------------|--------------|
| (생성 중) | `생성 중` | — | — | — |
| `AWAITING_INSPECTION` | `채택 대기중` | ✅ | ❌ (AI 초안 직접 접근 차단) | ❌ |
| `AWAITING_ADOPTION` | `채택 대기중` | ✅ (계속 채택 가능) | ✅ (검수된 리포트만) | ✅ |
| `COUNSELING` | `상담 중` | — | ✅ | ❌ |
| `MATCHED` | `완료` | — | ✅ | ❌ |

> `GET /reports` 목록 API의 `status` 쿼리 파라미터 허용값: `생성 중` / `채택 대기중` / `상담 중` / `완료`

---

## 4. 경쟁 검수 모델

- **정의:** 동일한 AI 초안을 여러 사정사가 **독립적으로** 채택·검수·등록할 수 있는 구조
- **목적:** 사용자가 복수의 검수 리포트를 비교하여 원하는 사정사를 직접 선택
- **격리 규칙:** 각 사정사의 수정 내용은 본인 작업 공간에만 반영됨 (타 사정사와 격리)
- **제거 시점:** 채택해도 목록에서 제거되지 않음 — 사용자가 매칭을 확정한 시점에 해당 케이스가 다른 사정사의 채택 가능 목록에서 제거됨

---


## 5. 매칭 플로우 상세

### 매칭 요청 (즉시 연결)
- 사용자가 검수 리포트 목록에서 사정사를 **직접 선택** (알고리즘 추천 아님)
- 선택 즉시 매칭 확정 + WebSocket 채팅 채널 개설 + 양측 FCM/APNs 알림 — **사정사 수락 단계 없음**
- API 응답에 `chatRoomId` 포함
- API: `POST /matches/{reportID}` — Body: `{ "adjusterId": "uuid" }`
- **ChatRoom 생성 책임**: backend-developer가 매칭 서비스 로직 안에서 `ChatService.createRoom(userId, adjusterId)`를 호출한다. realtime-developer는 `ChatService.createRoom()`을 구현하고 노출한다.
- 동시에 복수 사정사에게 매칭 요청 **불가** (1건씩 순차 요청)
- 이미 진행 중인 상담이 있으면 `DUPLICATE_RESOURCE(409)` 반환

### 제약
- 보험업법 §189: 협상·합의 대리 기능 미제공. 정보 제공과 사정사 연결만 수행.
- 거절·수락 엔드포인트 없음 — 사용자가 선택하면 바로 COUNSELING 전이.

---

## 7. 인증·토큰 정책

| 항목 | 값 | 비고 |
|------|-----|------|
| Access Token 만료 | **15분** | JWT stateless |
| Refresh Token 만료 | **30일** | Redis TTL 저장 (`refresh:{userId}`) |
| Redis 저장소 | ElastiCache Redis | |
| 소셜 로그인 | 카카오, 네이버 OAuth2 | `provider`: `kakao` / `naver` |
| CSRF 방지 | OAuth2 콜백 시 `state` 파라미터 사용 (선택) | |
| 비밀번호 | 소셜 전용이라 비밀번호 없음 | 비번 변경 API 불필요 |
| 로그인 실패 응답 | 미존재 아이디·비밀번호 불일치 **동일 메시지** | 계정 존재 여부 노출 방지 |
| 신규 사용자 판별 | 로그인 콜백 응답에 `isNewUser: boolean` 포함 | |
| Device Token | 로그인 시 FCM device token 함께 등록 | 푸시 알림 수신용 |
| 로그아웃 멱등 처리 | 이미 로그아웃 상태여도 정상 처리 (중복 요청 무시) | |
| 소셜 계정 연결 | `social_accounts` 테이블로 소셜 ID ↔ 내부 User 매핑 | |

---

## 8. 구독 플랜 (손해사정사 전용)

| 플랜  | API `tier` 값 | `subscription_plan` DB 값 | 포함 혜택 |
|-----|-------------|--------------------------|---------|
| 미검증 | — | `none` | 미검증 손해사정사는 손해 사정 관련 어떤 것도 할 수 없다 |
| 기본  | `BASIC` | `basic` | AI 리포트 열람·수정·서명, 케이스 채택 |
| 프로  | `PRO` | `premium` | 기본 + 검수 리포트 목록 상단 노출 |

> `POST /subscriptions` Body: `{ "tier": "BASIC" | "PRO", "paymentMethod": "pg_token_xxx" }`  
> 미검증 사정사가 구독 시도 시 `FORBIDDEN(403)` 반환.

- 결제 주기: MONTHLY
- PG사: 토스페이먼츠
- 구독 만료 시 상단 노출 혜택 즉시 해제
- 구독 응답 필드: `subscriptionId`, `tier`, `status: "ACTIVE"`, `expiresAt`
- **구독 취소·현재 구독 조회 API 미구현** — 별도 엔드포인트 추가 검토 필요 `[미결]`

---

## 9. 사정사 자격 신청 플로우

### 신청 (`POST /users/adjuster-applications`)
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `name` | string | Y | 실명 |
| `speciality` | string | Y | 자격 분야: `신체` / `교통` |
| `licenseNo` | string | N* | 자격증 번호 (PDF 미제출 시 필수) |
| `licenseImageUrl` | string | N* | 자격증 PDF S3 URL (번호 미입력 시 필수) |
| `career` | int | N | 연차 |
| `introduce` | string | N | 자기소개 |

> `licenseNo`와 `licenseImageUrl` 중 **최소 하나는 필수**

### 신청서 상태 (`ADJUSTER_APPLICATIONS.status`)
| 값 | 설명 |
|----|------|
| `PENDING` | 심사 대기 중 |
| `ACCEPTED` | 승인 완료 (ADMIN 처리) → `CERTIFICATED_ADJUSTER`로 활성화 |
| `REJECTED` | 반려 (reason 필드 포함 가능) |

### 관리자 처리
- 승인: `POST /admins/adjuster-applications/{applicationID}/accept`
- 반려: `POST /admins/adjuster-applications/{applicationID}/rejects` (Body: `{ "reason": "..." }`)
- 이미 처리된 신청에 재처리 시: `UNSUPPORTED_OPERATION(400)`
- 승인 시: 해당 유저를 `CERTIFICATED_ADJUSTER`로 전환 + 프로필 활성화 + 신청자 알림 발송
- 자격번호+이름을 금융위원회 공식 명부와 대조 권장 (현재 수동 처리)

---

## 10. 주요 API 엔드포인트 (Spring Boot 담당)

> base URL = `https://example.com/api/v1`  
> 로그인은 OAuth2 소셜 로그인만 사용. 자체 로그인 없음.

### auth 도메인
| 기능 | Method | Path |
|------|--------|------|
| 회원가입 | POST | `/auth/register` |
| 소셜 로그인 콜백 | GET | `/auth/oauth2/{provider}/callback` |
| 토큰 갱신 | POST | `/auth/refresh` |
| 로그아웃 | POST | `/auth/logout` |

### user 도메인
| 기능 | Method | Path |
|------|--------|------|
| 내 정보 조회 | GET | `/users/me` |
| 내 정보 수정 | PATCH | `/users/me` |
| 회원 탈퇴 | DELETE | `/users/me` |
| 사정사 자격 신청 | POST | `/users/adjuster-applications` |

### report 도메인
| 기능 | Method | Path |
|------|--------|------|
| 리포트 생성 요청 (비동기) | POST | `/reports` |
| 리포트 목록 조회 | GET | `/reports?status={status}&page={page}` |
| 리포트 상세 조회 | GET | `/reports/{reportID}` |
| 리포트 검수·수정 | PATCH | `/reports/{reportID}` |

### matching 도메인
| 기능 | Method | Path |
|------|--------|------|
| 상담 신청 (즉시 매칭) | POST | `/matches/{reportID}` |

### chat 도메인
| 기능 | Method | Path |
|------|--------|------|
| 채팅방 목록 조회 | GET | `/chats` |

> 메시지 송수신은 WebSocket(STOMP)으로 처리. REST는 목록 조회만.

### payment 도메인
| 기능 | Method | Path |
|------|--------|------|
| 구독 신청 | POST | `/subscriptions` |
| 결제 내역 조회 | GET | `/payments/history` |

### admin 도메인
| 기능 | Method | Path |
|------|--------|------|
| 자격 신청 목록 조회 | GET | `/admins/adjuster-applications` |
| 자격 신청 승인 | POST | `/admins/adjuster-applications/{applicationID}/accept` |
| 자격 신청 반려 | POST | `/admins/adjuster-applications/{applicationID}/rejects` |

---

## 11. 에러 코드 전체 목록

```
// 400 Bad Request — 요청 자체가 잘못됨 (클라이언트 잘못)
INVALID_REQUEST          // 요청 형식/구조 이상 (깨진 JSON, 타입 불일치, 잘못된 파라미터)
VALIDATION_ERROR         // 필드 값이 검증 규칙 위반 (형식·길이·범위 등)
MISSING_REQUIRED_FIELD   // 필수 입력값 누락
UNSUPPORTED_OPERATION    // 허용되지 않는 동작 (MVP 미지원 보험사, 이미 처리된 신청 재처리 등)

// 401 Unauthorized — 인증 실패
INVALID_TOKEN            // 토큰 위조·변조 또는 서명/형식 오류
EXPIRED_TOKEN            // 토큰 유효기간 만료 → Refresh로 재발급 필요
LOGIN_REQUIRED           // 비로그인 상태로 인증 필요 리소스 접근

// 403 Forbidden — 인증은 됐지만 권한 없음
FORBIDDEN                // 권한 부족 (미활성 사정사 채택 API, 타인 리포트 접근, 비 ADMIN 등)

// 404 Not Found — 대상 리소스 없음
USER_NOT_FOUND           // 해당 사용자 없음
POST_NOT_FOUND           // 해당 리포트 / 매칭 요청 / 신청서 없음
SUBSCRIPTION_NOT_FOUND   // 해당 구독 정보 없음

// 409 Conflict — 현재 리소스 상태와 충돌
DUPLICATE_RESOURCE       // 이미 존재하는 리소스 재생성 (닉네임 중복, 이미 진행 중인 상담 등)

// 422 Unprocessable Entity — 형식은 맞으나 비즈니스 규칙상 처리 불가
PAYMENT_FAILED           // 결제 처리 실패 (PG 거절, 카드 한도 초과, 잔액 부족 등)

// 500 Internal Server Error — 서버 내부 오류
INTERNAL_SERVER_ERROR    // 처리되지 않은 서버 예외
DATABASE_ERROR           // DB 조회/저장 실패
EXTERNAL_API_ERROR       // 외부 연동 실패 (PG, 카카오/네이버 OAuth, OCR, LLM 등)

// 503 Service Unavailable — 서버 일시 이용 불가
SERVICE_UNAVAILABLE      // 점검·배포·과부하 (보통 Retry-After 헤더 동반)
```

---

## 12. 컴플라이언스 제약 (법령 근거)

| 규칙 | 근거 법령 | 코드 영향 |
|------|---------|---------|
| 금융위 등록 자격자만 사정사 활성화 허용 | 보험업법 §186 | `ADJUSTER` 계정 활성화 ADMIN 수동 처리. 미활성화 시 채택 API 403 |
| 확정 결과물에 등록 손해사정사 서명·자격 표시 필수 | 보험업법 §188 | publish API 요청 시 `adjuster_license_no`, `adjuster_name`, `signed_at` 필수 |
| 서명된 리포트 3년 보존 | 보험업법 §188 / 개인정보보호법 | 탈퇴 시 식별정보 익명화, 리포트 원본은 3년 유지 |
| 협상·합의 대리 기능 미제공 | 보험업법 §189 | 매칭 기능은 연결만. 대리 행위로 해석될 UI·API 문구 금지 |
| 보존 기간 경과 후 개인정보 파기 | 개인정보보호법 §21 | 3년 경과 후 파기 스케줄러 필요 `[미구현]` |
| 보상금액 단정 표현 금지 | 금소법 §17·§19 | API 응답에 확정적 보상금액 직접 노출 금지. `claimedMinAmount` / `claimedMaxAmount` 범위로만 표현 |
| 진단·의료 판단 표현 금지 | 의료법 §17·§22 | AI 리포트 응답에 의학적 단정 문구 금지 |

---

## 13. 핵심 테이블 목적 정의

### REPORT_REVIEWS (사정사 검수 테이블)
- **목적**: 사정사가 AI 초안(REPORTS)을 채택·수정한 내용을 저장하는 작업 공간
- **생성 시점**: 사정사가 케이스를 채택(`adopt`)할 때 행 생성
- **확정 흐름**: `검수 수정(PATCH, 서명 필드 없이)` → `서명 확정(PATCH, status=AWAITING_ADOPTION + 서명 필드)` — 단일 API로 처리
- **격리 규칙**: 경쟁 검수 모델에 따라 동일 AI 초안에 여러 사정사의 REPORT_REVIEWS 행이 존재할 수 있음. 조회 시 반드시 `adjuster_id` 필터링
- **RAG 피드백**: `review` 필드는 AI 개선 피드백 전용 — publish·서명 검증 대상 아님

### ADJUSTER_REVIEW (사용자 평가 테이블)
- **목적**: 매칭 완료(MATCHED) 후 사용자가 담당 사정사를 평가한 기록
- **생성 시점**: 사용자가 매칭 종료 후 평가 제출 시
- **필드**: `score`(정수), `review`(텍스트)
- **제약**: 사용자 1인 + 사정사 1인 조합으로 중복 평가 방지

---

## 14. ERD 핵심 필드 참조

### REPORTS
| 필드 | 타입 | 설명 |
|------|------|------|
| `accident_type` | enum | `질병`, `상해`, `후유장해`, `복합` ⚠️ |
| `status` | enum | `AWAITING_INSPECTION`, `AWAITING_ADOPTION`, `COUNSELING`, `MATCHED` |
| `claimed_min_amount` | bigint | 최소 청구 금액 (단정 표현 금지 — 범위로 표현) |
| `claimed_max_amount` | bigint | 최대 청구 금액 |
| `offered_amount` | bigint | 보험사 지급 금액 |
| `applicable_guarantees` | string[] | 적용 가능 보장 목록 |
| `omitted_special_contract` | string[] | 누락 가능 특약 목록 |
| `basis_terms_precedents` | string[] | 근거 약관·판례 |
| `issue` | string[] | 검토 쟁점 목록 |
| `adjuster_id` | uuid | 담당 사정사 ID (매칭 전 null) |

> ⚠️ **불일치 주의:** DB의 `accident_type` enum 값은 `질병/상해/후유장해/복합`이나, `POST /reports` API 요청 파라미터 `accidentType`은 `신체/교통`으로 명세됨. 서버 내부에서 매핑 처리하는지 확인 필요. `ADJUSTER_PROFILES.speciality`의 `신체/교통`과 동일 체계일 가능성 있음.

### ADJUSTER_PROFILES
| 필드 | 타입 | 설명 |
|------|------|------|
| `license_no` | varchar | 금융위원회 등록번호 (UK) |
| `speciality` | varchar | `신체`, `교통` |
| `subscription_plan` | enum | `none`, `basic`, `premium` |

### SUBSCRIPTIONS
| 필드 | 타입 | 설명 |
|------|------|------|
| `plan` | enum | `none`, `basic`, `premium` (ADJUSTER_PROFILES.subscription_plan과 동일 체계) |
| `billing_cycle` | enum | `MONTHLY` |
| `status` | enum | `ACTIVE`, `EXPIRED`, `CANCELED` |
| `expires_at` | date | 구독 만료일 |

### PAYMENTS
| 필드 | 타입 | 설명 |
|------|------|------|
| `amount` | int | 결제 금액 (원) |
| `type` | enum | `SUBSCRIPTION` |
| `status` | enum | `PAID` |
| `paid_at` | datetime | 결제 완료 시각 |

### ADJUSTER_APPLICATIONS
| 필드 | 타입 | 설명 |
|------|------|------|
| `name` | varchar | 실명 |
| `speciality` | varchar | `신체` / `교통` |
| `license_no` | varchar | 자격증 번호 (nullable) |
| `license_image_url` | varchar | 자격증 PDF S3 URL (nullable) |
| `career` | int | 연차 (nullable) |
| `introduce` | text | 자기소개 (nullable) |
| `status` | enum | `PENDING`, `ACCEPTED`, `REJECTED` |

---

## 15. API 응답 구조

```json
// 성공 시
{
  "status": "200",
  "message": "정상 처리되었습니다.",
  "data": { ... }
}

// 실패 시
{
  "status": "400",
  "code": "ERROR_CODE",
  "message": "에러 메시지"
}
```

> 리포트 생성(`POST /reports`)은 **비동기** — 202 Accepted 반환.  
> 결제 내역 조회 응답에는 영수증 발급·구독 해제가 별도 엔드포인트로 분리 권장 (미구현).

---

## 16. 기능별 비즈니스 규칙 요약 (기능리스트 출처)

> API 명세서에 없는 선행조건·트리거·예외 처리 규칙. 구현 시 반드시 참조.

### auth
| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 회원가입 | 소셜 인증 완료, 미가입 상태 | social_accounts 연결 + 닉네임 등록 + JWT 발급 | 이미 연동된 소셜 계정 → 로그인 처리로 전환; 닉네임 중복 → 거부 |
| 소셜 로그인 | provider 동의 완료, 인가코드 수신 | 인가코드 → 소셜 토큰 교환 → 기존 회원: JWT 발급, 신규: 가입 플로우 연결; 로그인 시 device token 등록 | 미지원 provider; 인가코드 만료/무효 |
| 로그아웃 | 로그인 상태 (유효 토큰 보유) | Redis Refresh Token 폐기; 이미 로그아웃 상태여도 멱<br/>등 처리 | Access Token은 stateless — 15분 만료 전까지 유효 |
| 회원탈퇴 | 로그인 + 본인 확인 | 계정 익명화 + Refresh Token 삭제 + S3 접근 차단 | 진행 중 매칭/상담 처리 정책 미결 `[미결]`; 서명 완료 리포트는 3년 보존 |

### user
| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 회원 정보 조회 | 로그인 | 본인 정보 반환; 비밀번호 등 민감 필드 제외 | 비로그인·만료 → 401 |
| 회원 정보 수정 | 로그인 | 닉네임·이메일 수정 (비밀번호 변경 없음) | 닉네임 중복 409; 형식 위반 400 |
| 사정사 자격 신청 | 로그인(USER), 미인증 사정사 | 신청서 생성(PENDING); 운영팀 승인 전까지 채택·검수 불가 | 이미 인증/신청 진행 중 → 중복 신청 불가 |

### report
| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 사건 정보 입력(리포트 생성) | 로그인(USER); 보험사·상품 선택 + 사고정보(USER_CLAIMS) 완료 | 비동기 AI 파이프라인 시작 → 완료 시 푸시 알림 or polling | 미적재 약관/미지원 보험사; 필수값 누락 |
| 리포트 목록 조회 | 로그인 | 사용자: 본인 리포트; 사정사: 본인 채택분 리포트; 타인 리포트 미노출 | — |
| 리포트 상세 조회 | 로그인; 본인 리포트 or 채택 사정사 | 검수 완료 리포트만 열람; 서명 전 AI 초안은 사용자에게 비노출 | 타인 접근 403; 미존재 404 |
| 초안 검수·수정 | 로그인(사정사); 채택 상태 | 리포트 body로 상태 전이 | 허용되지 않는 상태 전이; 권한 없음 403 |

> ⚠️ **미결:** 채택(adopt) 동작을 `PATCH /reports/{reportID}`로 처리하는지, 별도 엔드포인트가 필요한지 확인 필요.

### matching
| 기능 | 선행조건 | 핵심 동작                   | 주요 예외 |
|------|---------|-------------------------|---------|
| 상담 신청 (즉시 매칭) | 검수본 1건 이상(AWAITING_ADOPTION); 로그인(USER) | 사정사 선택 즉시 매칭 확정 + 채팅방 개설 + 양측 알림 — 사정사 수락 단계 없음 | 이미 매칭 진행 중/완료 → 409; 본인 리포트 아님 → 403 |

### chat
| 기능 | 선행조건 | 핵심 동작 | 비고 |
|------|---------|---------|------|
| 채팅방 목록 조회 | 로그인 | 본인 참여 채팅방 목록(마지막 메시지 포함) 반환 | 메시지 이력 조회 API 없음 (Spring Boot 범위 외) |
| 채팅 입장 | 로그인; 채팅방 참여자 | WebSocket(STOMP)으로 실시간 메시지 송수신 | REST API 없음; AI 챗봇은 FastAPI 담당 |

### payment
| 기능 | 선행조건 | 핵심 동작 | 비고 |
|------|---------|---------|------|
| 구독 신청 | 로그인(활성 사정사); PG 연동 | BASIC/PRO 플랜 결제 → 활성화 + 만료일 설정 | 구독 취소·현재 구독 조회 별도 필요 `[미결]` |
| 결제 내역 조회 | 로그인 | 본인 결제 내역 목록 반환 | 영수증 발급·구독 해제는 별도 엔드포인트 권장 `[미구현]` |

---

## 17. 변경 이력

| 날짜 | 변경 내용                                                                                                                                              | 사유 |
|------|----------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 2026-06-14 | 매칭 플로우 수정: 사정사 수락 단계 제거. 사용자가 사정사 선택 시 즉시 COUNSELING 전이. `/matches/{reportID}/accept` API 삭제. 섹션 5·10·16 반영. | 실제 기획 확인 — 수락/거절 없는 즉시 연결 구조 |
| 2026-06-14 | 기능리스트 20개 페이지 동기화. device token, 로그아웃 멱등, 매칭 24h 만료, 거절 API 미결, 채택 API 미결, 구독 취소 미구현, USER_CLAIMS 선행조건, 기능별 비즈니스 규칙 표(섹션 16) 추가.                   | 기능리스트 기반 비즈니스 규칙 보완 |
| 2026-06-14 | Notion API 명세서 20개 페이지 전수 동기화. userType/Role 구분, 에러코드 전체, adjuster-applications 플로우, accidentType 불일치 주의, 매칭 경로(/matches), admin API, 결제/구독 상세 추가. | 최초 API 명세 기반 정합성 확보 |
| 2026-06-09 | 초기<br/> 구성                                                                                                                                         | 환경 세팅 완료 후 하네스 등록 |
