// 고객(USER) 주요 API 단일 격리 부하테스트 (#300) — 엔드포인트별 knee 탐색.
// 한 번 실행 = 대상 API 1개 · RPS 1개. 계단(10→20→40→80→160)은 스크립트가 아니라 RPS만 바꿔 다시 실행해서 밟는다.
// 단계마다 프로세스를 새로 띄워야 rps 태그로 단계별 p95·실패율이 갈리고, 앞 단계의 대기열·GC 압박도 끌고 가지 않는다.
//
// 실행 (k6 인스턴스 → 워커 NodePort 직결, ALB 미경유):
//   export DEV_LOGIN_KEY='<app/backend-dev의 DEV_LOGIN_SECRET>' K6_STATSD_ENABLE_TAGS=true K6_STATSD_ADDR=127.0.0.1:8125 && k6 run --out output-statsd --tag profile=user-single -e BASE_URL=http://10.0.137.77:30080 -e TARGET=reports_list -e RPS=20 -e DURATION=60s -e K6_USERS=600 user-single-api.k6.js
//
// 읽는 env: BASE_URL · DEV_LOGIN_KEY · TARGET · RPS · DURATION · K6_USERS · MAX_VUS
//
// 주의
// - K6_USERS는 시드된 고객 수(k6-user-1~600) 이하로 둔다. dev-login은 없는 닉네임이면 빈 계정을 새로 만든다.
// - create_report는 맨 마지막에 단독으로 돌린다. 실행할 때마다 리포트가 쌓이고(case_no가 K6- 형식이 아니라
//   시드 정리 SQL에 안 걸린다) 아웃박스 → SQS 발행도 실제로 일어난다.
// - 사정사 시나리오와 동시에 돌리지 않는다. backend·RDS를 같이 써서 서로의 측정을 오염시킨다.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const DEV_KEY = __ENV.DEV_LOGIN_KEY || ''; // 로컬은 시크릿이 없어 헤더 검증을 건너뛴다
const USERS = Number(__ENV.K6_USERS || 600);
const TARGET = __ENV.TARGET || 'reports_list';
const RPS = Number(__ENV.RPS || 20);
const DURATION = __ENV.DURATION || '60s';
const MAX_VUS = Number(__ENV.MAX_VUS || 300);
const ACCIDENT_TYPES = ['medical_indemnity', 'traffic', 'disability', 'cancer_diagnosis', 'fire', 'liability', 'other'];

// 대상별 준비물. setup이 계정마다 미리 확보한다 — 이터레이션 안에서 조회하면 단독 측정이 깨진다.
const NEEDS = {
  reports_list: {},
  report_detail: { report: true },
  analysis_status: { report: true },
  chats_list: {},
  chat_messages: { room: true },
  create_report: {},
};

export const options = {
  scenarios: {
    single: {
      executor: 'constant-arrival-rate', // 개방형 — 응답이 느려져도 도착률을 유지해 포화를 드러낸다
      exec: 'hit',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(RPS * 2, 100),
      maxVUs: MAX_VUS,
      tags: { target: TARGET, rps: String(RPS) },
    },
  },
  // target 태그로 걸어야 setup 요청(로그인·ID 확보)이 판정에서 빠진다.
  thresholds: {
    [`http_req_failed{target:${TARGET}}`]: ['rate<0.01'],
    [`http_req_duration{target:${TARGET}}`]: ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '10m',
  discardResponseBodies: true,
};

function authHeaders(token) {
  // 쿠키 jar를 거치지 않고 헤더로 직접 싣는다. dev의 access_token은 Secure라 평문 http로는 jar가 되돌려
  // 보내지 않고, jar 자체도 이터레이션마다 초기화된다(#297 원인 1·2).
  return { 'Content-Type': 'application/json', Cookie: `access_token=${token}` };
}

function json(res, path) {
  try {
    return res.json(path);
  } catch (err) {
    return null; // 비-JSON 응답(Tomcat 400 HTML 등)은 없는 값으로 취급
  }
}

export function setup() {
  const need = NEEDS[TARGET];
  if (!need) {
    throw new Error(`알 수 없는 TARGET: ${TARGET} (${Object.keys(NEEDS).join(', ')})`);
  }
  const accounts = [];
  for (let i = 1; i <= USERS; i += 1) {
    const res = http.post(`${BASE}/auth/dev/login`, JSON.stringify({ nickname: `k6-user-${i}` }), {
      headers: { 'Content-Type': 'application/json', 'X-Dev-Login-Key': DEV_KEY },
      responseType: 'text',
      tags: { phase: 'setup' },
    });
    const cookie = (res.cookies.access_token || [])[0];
    if (!cookie) {
      continue; // 무인증 계정이 섞이면 1ms 401로 이터레이션을 삼켜 측정이 왜곡된다(#297 원인 3)
    }
    const acc = { token: cookie.value };
    if (need.report) {
      acc.reportId = firstReportId(acc.token);
    }
    if (need.room) {
      acc.roomId = durableRoomId(acc.token);
    }
    if ((need.report && !acc.reportId) || (need.room && !acc.roomId)) {
      continue; // 시드가 없는 계정도 버린다(no-op 이터레이션 방지)
    }
    accounts.push(acc);
  }
  if (accounts.length === 0) {
    throw new Error('쓸 수 있는 계정이 0개 — BASE_URL·dev-login 게이트·DEV_LOGIN_KEY·시드를 확인할 것');
  }
  console.log(`setup 완료: 계정 ${accounts.length}/${USERS}개, TARGET=${TARGET}, ${RPS} RPS / ${DURATION}`);
  return accounts;
}

function firstReportId(token) {
  const res = http.get(`${BASE}/reports?page=1&size=1`,
    { headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup' } });
  const list = json(res, 'data.list');
  return list && list.length ? list[0].report_id : null;
}

function durableRoomId(token) {
  // 채팅방 목록은 기본 20개씩 페이지로 나온다(#369). 시드 계정의 방은 38개라 한 번에 다 보려면 size를 올려야 한다.
  const res = http.get(`${BASE}/chats?size=50`,
    { headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup' } });
  const rooms = json(res, 'data.rooms') || [];
  // M 풀 방(메시지 41건)만 쓴다 — 기본 페이지(30)를 넘겨 커서 조회가 실제로 도는 유일한 방이다. 메시지 1건짜리
  // D 풀 방으로 대신하면 다른 것을 재게 되므로, 못 찾으면 그 계정은 버린다.
  const durable = rooms.find((room) => room.room_status === 'ACTIVE' && room.match_status === 'ACCEPTED');
  return durable ? durable.chat_room_id : null;
}

export function hit(accounts) {
  // VU 번호가 아니라 이터레이션 번호로 계정을 돌린다. VU 수가 계정 수보다 적어도 시드 전체에 고르게 퍼져야
  // 일부 계정 데이터만 캐시에 올라와 비현실적으로 빨라지는 일이 없다.
  const acc = accounts[exec.scenario.iterationInTest % accounts.length];
  const params = { headers: authHeaders(acc.token), tags: { name: TARGET } };
  switch (TARGET) {
    case 'reports_list':
      ok(http.get(`${BASE}/reports?page=1&size=10`, params), 200);
      break;
    case 'report_detail':
      ok(http.get(`${BASE}/reports/${acc.reportId}`, params), 200);
      break;
    case 'analysis_status':
      ok(http.get(`${BASE}/reports/${acc.reportId}/analysis-status`, params), 200);
      break;
    case 'chats_list':
      ok(http.get(`${BASE}/chats`, params), 200);
      break;
    case 'chat_messages':
      ok(http.get(`${BASE}/chats/${acc.roomId}/messages?size=30`, params), 200);
      break;
    case 'create_report':
      ok(http.post(`${BASE}/reports`, JSON.stringify(reportBody()), params), 202);
      break;
    default:
      throw new Error(`알 수 없는 TARGET: ${TARGET}`);
  }
}

function ok(res, expected) {
  check(res, { [`${TARGET} ${expected}`]: (r) => r.status === expected });
}

function reportBody() {
  const today = new Date().toISOString().slice(0, 10);
  return {
    product_id: null,
    accident_type: ACCIDENT_TYPES[Math.floor(Math.random() * ACCIDENT_TYPES.length)],
    accident_date: today,
    diagnosis: ['부하테스트 진단'],
    offered_amount: 1420000,
    hospitalizations: [],
    description: 'k6 단일 API 부하테스트',
    additional_information: null,
    // 합성 S3 키라 OCR 워커는 실패해도 무방하다. 아웃박스 → SQS 발행량은 그대로 측정된다.
    documents: [{
      s3_url: 'https://bucket.s3.ap-northeast-2.amazonaws.com/uploads/k6.pdf',
      name: 'k6.pdf',
      report_type: null,
      file_type: null,
    }],
    question: null,
  };
}
