# QA E2E (Playwright · API 테스트)

QA 체크리스트의 **`결과 = 미확인`** 항목을 자동 검증한다. 이 항목들은 대부분 `백엔드예외`·`상태전이`
(잘못된 요청 → HTTP 상태/ErrorCode 확인)라, 브라우저 UI 클릭이 아니라 **Playwright의 API 테스트
모드(`request`)**로 백엔드를 직접 때린다. 통합 UI·WebSocket 소수만 별도 처리한다.

## 설치 · 실행

```bash
cd qa-e2e
npm install
npx playwright install            # 브라우저 바이너리(위조토큰/CORS의 newContext에 필요)
cp .env.example .env              # API_BASE_URL 을 실제 백엔드 주소로 수정

# 1단계 — 로그인 불필요(즉시 실행 가능)
npm run test:unauth

# 2단계 — dev-login 세션 필요(로컬 백엔드 + app.dev-login.enabled=true)
npm run test:auth

npm run report                    # HTML 리포트
```

`API_BASE_URL` 은 프론트(vercel)가 호출하는 백엔드 주소다. 컨트롤러는 `/api/v1` prefix 없이
루트 기준(`/auth`, `/users`, `/reports`, `/chats` …)이다.

## 커버리지 (현재 스캐폴드)

| 파일 | 단계 | 커버 QA ID | 인증 |
|------|------|-----------|------|
| `tests/unauth.spec.ts` | 1 | USER-04·18·23, ACT-63, RPT-07, PRV-25·50, CHAT-05, NOTI-04·24, SEC-06 | 불필요 |
| `tests/forged-token.spec.ts` | 1 | USER-05, SEC-08 | 위조쿠키 |
| `tests/cors.spec.ts` | 1 | FMT-08, FMT-09 | 불필요 |
| `tests/response-format.spec.ts` | 1 | SEC-11, FMT-04, EXC-05 | 불필요 |
| `tests/auth-session.spec.ts` | 2 | SEC-01, FMT-03, SEC-03 | dev-login |

> 미확인 약 218건 중 **1단계로 즉시 검증 가능한 골격**이다. 나머지(권한 403·상태전이·채팅·WebSocket)는
> 아래 확장 가이드대로 늘린다.

## 인증 세션 확보

- **로컬 백엔드**(`app.dev-login.enabled=true`): `tests/auth.setup.ts` 가 `POST /auth/dev/login`
  으로 세션을 따 `.auth/user.json`(storageState)에 저장한다. `test:auth` 가 자동으로 setup 을 먼저 돌린다.
- **원격 dev/prod**: 이 경로가 없다(빈 미등록). 브라우저에서 로그인 후 `access_token` 쿠키 값을 복사해
  `.auth/user.json` 을 수동으로 채운다(형식은 `auth.setup.ts` 주석 참고).

## 확장 가이드

- **미인증 401**: `unauth.spec.ts` 의 `CASES` 배열에 `{ id, method, path }` 를 추가.
- **권한 403(role 불일치)**: CERT/UNCERT/USER 별 세션이 필요 → dev-login 확장 또는 계정별 storageState.
- **상태전이(RSM/RVSM/PRV)**: 리포트 생성→검수→상담 등 **선행 데이터 시드**가 필요. 시나리오 spec 으로
  여러 API 를 순차 호출해 상태를 만들고 검증한다.
- **WebSocket(WS-\*)**: `request` 로는 불가. STOMP 클라이언트(`@stomp/stompjs` + `ws`)로 핸드셰이크·
  구독 인가를 테스트하는 별도 spec 이 필요하다.

## 알려진 제약

- `npx playwright install` 로 브라우저 설치가 선행돼야 `newContext`(위조토큰·CORS)가 동작한다.
- 만료 토큰(EXPIRED_TOKEN) 케이스는 `JWT_SECRET` 이 있어야 생성 가능 → 현재 스킵(위조=INVALID_TOKEN 만).
- `EXC-05`(없는 경로), size=0 등 **결함 후보**는 기대값을 단정하지 않고 실제 상태코드를 기록·감시한다.
- 이 스위트는 백엔드 레포에 함께 뒀지만 Java 빌드(checkstyle/gradle)와 무관하다. 별도 레포로 분리해도 된다.
