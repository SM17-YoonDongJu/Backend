# Domain Glossary — 손해사정 도메인

> **출처:** Notion — 프로젝트 개요, 기능 명세(7·8·9·11), 유저 스토리, ERD  
> **규칙:** 이 파일의 모든 항목은 위 Notion 페이지 출처로만 작성한다. 임의 해석·추측 금지.  


---

## 1. 서비스 개요

보험금 지급 결과에 의문이 있는 사용자를 대상으로:
1. **AI 리포트** — 적용 가능 보장·누락 특약·분쟁 포인트 분석 (FastAPI 담당)
2. **손해사정사 연결** — AI 초안을 검수한 사정사 중 사용자가 직접 선택해 매칭 (Spring Boot 담당)

> 보험업법 §189: 협상·합의 대리는 제공하지 않는다. 정보 제공 및 손해사정사 연결만 수행.

---

## 2. 핵심 행위자 (Role)

| 역할 | ERD Enum 값 | 설명 | 접근 가능 기능 |
|------|------------|------|--------------|
| 일반 사용자 | `USER` | 보험금 검토가 필요한 보험 계약자 | 리포트 생성 요청, 검수 리포트 목록 조회, 매칭 요청 |
| 자격 사정사 | `CERTIFICATED_ADJUSTER` | 금융위원회 등록 손해사정사, 운영팀 활성화 완료 | 케이스 채택, 검수·등록, 심층 분석 리포트 열람 |
| 미자격 사정사 | `UNCERTIFICATED_ADJUSTER` | 신청 후 미활성화 상태 | 로그인 가능, 채택 API 호출 시 403 |
| 관리자 | `ADMIN` | 운영팀 | 전체 관리, 사정사 계정 활성화, 구독 관리 |

> 보험업법 §186: 금융위 등록 자격자만 활성화 허용. 미활성화 사정사는 로그인은 되나 케이스 채택 불가(403).

---

## 3. 리포트 상태머신 (REPORTS.status)

```
[AI 초안 생성 완료]
        ↓
AWAITING_INSPECTION  ← 검수 대기. 사정사 채택 가능 목록에 노출됨.
        ↓  (1건 이상 검수 리포트 등록 완료)
AWAITING_ADOPTION    ← 사용자 선택 대기. 복수 검수 리포트 비교 가능.
        ↓  (사용자가 매칭 요청 → 사정사 수락)
COUNSELING           ← 채팅 중. WebSocket 채널 개설됨.
        ↓
MATCHED              ← 최종 매칭 완료.
```

### 상태별 규칙
| 상태 | 코드값 | 사정사 채택 가능 | 사용자 리포트 열람 | 매칭 요청 가능 |
|------|--------|----------------|------------------|--------------|
| 검수 대기 | `AWAITING_INSPECTION` | ✅ | ❌ (AI 초안 직접 접근 차단) | ❌ |
| 채택 대기 | `AWAITING_ADOPTION` | ✅ (계속 채택 가능) | ✅ (검수된 리포트만) | ✅ |
| 채팅 중 | `COUNSELING` | — | ✅ | ❌ |
| 매칭 완료 | `MATCHED` | — | ✅ | ❌ |

---

## 4. 경쟁 검수 모델

- **정의:** 동일한 AI 초안을 여러 사정사가 **독립적으로** 채택·검수·등록할 수 있는 구조
- **목적:** 사용자가 복수의 검수 리포트를 비교하여 원하는 사정사를 직접 선택
- **격리 규칙:** 각 사정사의 수정 내용은 본인 작업 공간에만 반영됨 (타 사정사와 격리)
- **제거 시점:** 채택해도 목록에서 제거되지 않음 — 사용자가 매칭을 확정한 시점에 해당 케이스가 다른 사정사의 채택 가능 목록에서 제거됨

---

## 5. 검수 리포트 확정 조건 (보험업법 §186·§188)

검수 리포트 등록(`POST /api/v1/adjuster/my-reviews/{review_id}/publish`) 시 **필수 포함 필드:**

```json
{
  "adjuster_license_no": "제2024-0001호",
  "adjuster_name":       "홍길동",
  "signed_at":           "2026-06-03T14:00:00Z"
}
```

- **서명 없이 등록 불가** (미서명 상태로 publish API 호출 시 거부)
- **등록 후 수정 불가** — 이력 관리 DB에 불변 저장
- **보존 기간:** 서명일로부터 **3년** (보험업법 §188 / 개인정보보호법)
- **탈퇴 시:** 사용자 식별정보는 익명화, 서명된 리포트·S3 원본은 보존 기간 동안 유지

---

## 6. 매칭 플로우 상세

### 매칭 요청
- 사용자가 검수 리포트 목록에서 사정사를 **직접 선택** (알고리즘 추천 아님)
- 동시에 복수 사정사에게 매칭 요청 **불가** (1건씩 순차 요청)
- 요청 후 해당 케이스는 다른 사정사 채택 가능 목록에서 제거됨

### 수락
- 수락: 매칭 확정 + WebSocket 채팅 채널 개설 + 양측 FCM/APNs 알림

### 제약
- 보험업법 §189: 협상·합의 대리 기능 미제공. 정보 제공과 사정사 연결만 수행.

---

## 7. 인증·토큰 정책

| 항목 | 값 | 비고 |
|------|-----|------|
| Access Token 만료 | **15분** | JWT stateless |
| Refresh Token 만료 | **30일** | Redis TTL 저장 (`refresh:{userId}`) |
| Redis 저장소 | ElastiCache Redis | |
| 소셜 로그인 | 카카오, 네이버 OAuth2 | |
| 비밀번호 | bcrypt 해시 | 평문 저장 금지 |
| 로그인 실패 응답 | 미존재 아이디·비밀번호 불일치 **동일 메시지** | 계정 존재 여부 노출 방지 |

---

## 8. 구독 플랜 (손해사정사 전용)

| 플랜  | `subscription_plan` 값 | 포함 혜택 |
|-----|----------------------|---------|
| 미검증 | `none` | `[미확정]` 미구독자 접근 범위 논의 필요 |
| 기본  | `basic` | AI 리포트 열람·수정·서명, 케이스 채택 |
| 프로  | `premium` | 기본 + 검수 리포트 목록 상단 노출 |

- 결제 주기: MONTHLY
- PG사: 토스페이먼츠 (확정 `[미확정]`)
- 구독 만료 시 상단 노출 혜택 즉시 해제

---

## 9. 주요 API 엔드포인트 (Spring Boot 담당)

| 기능 | Method | Path |
|------|--------|------|
| 회원가입 | POST | `/api/v1/auth/signup` |
| 로그인 | POST | `/api/v1/auth/login` |
| 소셜 로그인 콜백 | GET | `/api/v1/auth/oauth2/{provider}/callback` |
| 토큰 갱신 | POST | `/api/v1/auth/refresh` |
| 로그아웃 | POST | `/api/v1/auth/logout` |
| 내 정보 조회 | GET | `/api/v1/users/me` |
| 내 정보 수정 | PUT | `/api/v1/users/me` |
| 회원 탈퇴 | DELETE | `/api/v1/users/me` |
| 사정사 계정 신청 | POST | `/api/v1/adjusters/apply` |
| 사정사 프로필 등록 | POST | `/api/v1/adjusters/profile` |
| 채택 가능 목록 조회 | GET | `/api/v1/adjuster/available-cases` |
| 케이스 채택 | POST | `/api/v1/adjuster/available-cases/{report_id}/adopt` |
| 심층 분석 리포트 | GET | `/api/v1/adjuster/my-reviews/{review_id}/deep-analysis` |
| 검수 수정 | PATCH | `/api/v1/adjuster/my-reviews/{review_id}` |
| 검수 리포트 등록 | POST | `/api/v1/adjuster/my-reviews/{review_id}/publish` |
| 사용자 검수 리포트 목록 | GET | `/api/v1/reports/{report_id}/reviewed-list` |
| 매칭 요청 | POST | `/api/v1/reports/{report_id}/match-request` |
| 매칭 수락 | POST | `/api/v1/match-requests/{request_id}/accept` |
| 매칭 거절 | POST | `/api/v1/match-requests/{request_id}/reject` |
| WebSocket 채팅 | WS | `/ws/chat/{channel_id}?token={jwt}` |

---

## 10. 컴플라이언스 제약 (법령 근거)

| 규칙 | 근거 법령 | 코드 영향 |
|------|---------|---------|
| 금융위 등록 자격자만 사정사 활성화 허용 | 보험업법 §186 | `ADJUSTER` 계정 활성화 ADMIN 수동 처리. 미활성화 시 채택 API 403 |
| 확정 결과물에 등록 손해사정사 서명·자격 표시 필수 | 보험업법 §188 | publish API 요청 시 `adjuster_license_no`, `adjuster_name`, `signed_at` 필수 |
| 서명된 리포트 3년 보존 | 보험업법 §188 / 개인정보보호법 | 탈퇴 시 식별정보 익명화, 리포트 원본은 3년 유지 |
| 협상·합의 대리 기능 미제공 | 보험업법 §189 | 매칭 기능은 연결만. 대리 행위로 해석될 UI·API 문구 금지 |
| 보존 기간 경과 후 개인정보 파기 | 개인정보보호법 §21 | 3년 경과 후 파기 스케줄러 필요 `[미구현]` |
| 보상금액 단정 표현 금지 | 금소법 §17·§19 (가드레일 guardrail.disclosure) | API 응답에 확정적 보상금액 직접 노출 금지. `claimed_min_amount` / `claimed_max_amount` 범위로만 표현 |
| 진단·의료 판단 표현 금지 | 의료법 §17·§22 (가드레일 guardrail.medical) | AI 리포트 응답에 의학적 단정 문구 금지 |

---

## 11. ERD 핵심 필드 참조

### REPORTS
| 필드 | 타입 | 설명 |
|------|------|------|
| `accident_type` | enum | `질병`, `상해`, `후유장해`, `복합` |
| `status` | enum | `AWAITING_INSPECTION`, `AWAITING_ADOPTION`, `COUNSELING`, `MATCHED` |
| `claimed_min_amount` | bigint | 최소 청구 금액 (단정 표현 금지 — 범위로 표현) |
| `claimed_max_amount` | bigint | 최대 청구 금액 |
| `offered_amount` | bigint | 보험사 지급 금액 |

### ADJUSTER_PROFILES
| 필드 | 타입 | 설명 |
|------|------|------|
| `license_no` | varchar | 금융위원회 등록번호 (UK) |
| `speciality` | varchar | `신체`, `교통` |
| `subscription_plan` | enum | `none`, `basic`, `premium` |

### SUBSCRIPTIONS
| 필드 | 타입 | 설명 |
|------|------|------|
| `plan` | enum | `BASIC`, `PRO` |
| `billing_cycle` | enum | `MONTHLY` |
| `status` | enum | `ACTIVE`, `EXPIRED`, `CANCELED` |

---


