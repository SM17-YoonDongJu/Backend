// 고객(USER) 주요 API 단일 격리 부하테스트 (#300, 대상 확장 #405) — 엔드포인트별 knee 탐색.
// 한 번 실행 = 대상 API 1개 · RPS 1개. 계단(10→20→40→80→160)은 스크립트가 아니라 RPS만 바꿔 다시 실행해서 밟는다.
// 단계마다 프로세스를 새로 띄워야 rps 태그로 단계별 p95·실패율이 갈리고, 앞 단계의 대기열·GC 압박도 끌고 가지 않는다.
// 여러 API를 화면 단위로 섞어 보내는 혼합 시나리오는 user-journey.k6.js다.
//
// 실행 (k6 인스턴스 → 워커 NodePort 직결, ALB 미경유):
//   export DEV_LOGIN_KEY='<app/backend-dev의 DEV_LOGIN_SECRET>' BASE_URL=http://10.0.137.77:30080 K6_USERS=600 K6_STATSD_ENABLE_TAGS=true K6_STATSD_ADDR=127.0.0.1:8125
//   k6 run --out output-statsd --tag profile=user-single -e TARGET=reports_list -e RPS=20 -e DURATION=120s user-single-api.k6.js
//
// 읽는 env: BASE_URL · DEV_LOGIN_KEY · TARGET · RPS · DURATION · K6_USERS · MAX_VUS · NICKNAMES
// NICKNAMES: 로그인할 닉네임 목록 파일(한 줄에 하나, 스크립트 기준 상대 경로). 시드 닉네임이 시연용으로 바뀌어
//   k6-user-N이 없을 때 쓴다. 없으면 k6-user-1~K6_USERS로 로그인한다
// TARGET: TARGETS 표의 키(reports_list·report_detail·analysis_status·analysis_metrics·proposals_list·dashboard·me·
//   notifications·adjusters_list·chats_list·chat_room·chat_read·chat_send·chat_messages·create_report)
//
// 주의
// - K6_USERS는 시드된 고객 수(k6-user-1~600) 이하로 둔다. dev-login은 없는 닉네임이면 빈 계정을 새로 만든다.
// - 리포트 대상(report_detail·analysis_status·analysis_metrics·proposals_list)은 계정마다 시드 풀(P·D·M·I)에서 리포트를
//   몇 개씩 모아 매번 섞어 고른다. 풀마다 상세 경로가 다르다(M은 채택 리뷰가 있어 쿼리가 더 붙는다).
// - chat_read(POST /chats/{id}/read)는 읽음 커서만 갱신한다. chat_send(POST /chats/{id}/messages)는 방마다 메시지를
//   쌓는다(메시지 INSERT + 방 행 UPDATE + 커밋 뒤 Redis 발행). 둘 다 시드는 소모하지 않는다.
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
// setup 데이터는 VU마다 복사되므로 계정당 보관량을 작게 둔다(풀별 리포트 2건, 방 4개)
const REPORTS_PER_POOL = 2;
const ROOMS_PER_ACCOUNT = 4;
// open()은 init 단계에서만 부를 수 있다. setup만 쓰지만 VU마다 한 번씩 읽힌다(수 KB라 무시할 만하다).
const NICKNAMES = __ENV.NICKNAMES
  ? open(__ENV.NICKNAMES).split('\n').map((line) => line.trim()).filter(Boolean)
  : null;

// 대상별 준비물(needs)과 요청. 준비물은 setup이 계정마다 미리 확보한다 — 이터레이션 안에서 조회하면 단독 측정이 깨진다.
// needs: report = 계정의 리포트 목록, room = 활성 채팅방 목록, durableRoom = 메시지 41건짜리 M 풀 방
const TARGETS = {
  reports_list: { call: () => ['GET', '/reports?page=1&size=10'] },
  report_detail: { needs: 'report', call: (acc) => ['GET', `/reports/${pick(acc.reports)}`] },
  analysis_status: { needs: 'report', call: (acc) => ['GET', `/reports/${pick(acc.reports)}/analysis-status`] },
  analysis_metrics: { needs: 'report', call: (acc) => ['GET', `/reports/${pick(acc.reports)}/analysis-metrics`] },
  proposals_list: {
    needs: 'report',
    call: (acc) => ['GET', `/reports/${pick(acc.proposalReports)}/proposals?page=1&size=10`],
  },
  dashboard: { call: () => ['GET', '/users/me/dashboard'] },
  me: { call: () => ['GET', '/users/me'] },
  notifications: { call: () => ['GET', '/users/me/notifications'] },
  adjusters_list: { call: () => ['GET', '/adjusters?sort=rating&size=4'] }, // 홈 추천 사정사와 같은 호출
  chats_list: { call: () => ['GET', '/chats'] },
  chat_room: { needs: 'room', call: (acc) => ['GET', `/chats/${pick(acc.rooms)}`] },
  chat_read: { needs: 'room', call: (acc) => ['POST', `/chats/${pick(acc.rooms)}/read`, ''] },
  chat_send: {
    needs: 'room',
    call: (acc) => ['POST', `/chats/${pick(acc.rooms)}/messages`, JSON.stringify({ content: 'k6 부하테스트 메시지입니다.' })],
    expected: 201,
  },
  chat_messages: { needs: 'durableRoom', call: (acc) => ['GET', `/chats/${acc.durableRoom}/messages?size=30`] },
  create_report: { call: () => ['POST', '/reports', JSON.stringify(reportBody())], expected: 202 },
};

const SPEC = TARGETS[TARGET];

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
  if (!SPEC) {
    throw new Error(`알 수 없는 TARGET: ${TARGET} (${Object.keys(TARGETS).join(', ')})`);
  }
  const names = nicknames();
  const accounts = [];
  for (const nickname of names) {
    const res = http.post(`${BASE}/auth/dev/login`, JSON.stringify({ nickname }), {
      headers: { 'Content-Type': 'application/json', 'X-Dev-Login-Key': DEV_KEY },
      responseType: 'text',
      tags: { phase: 'setup' },
    });
    const cookie = (res.cookies.access_token || [])[0];
    if (!cookie) {
      continue; // 무인증 계정이 섞이면 1ms 401로 이터레이션을 삼켜 측정이 왜곡된다(#297 원인 3)
    }
    const acc = { token: cookie.value };
    if (SPEC.needs === 'report') {
      Object.assign(acc, reportsOf(acc.token));
    } else if (SPEC.needs === 'room' || SPEC.needs === 'durableRoom') {
      Object.assign(acc, roomsOf(acc.token));
    }
    if ((SPEC.needs === 'report' && acc.reports.length === 0)
        || (SPEC.needs === 'room' && acc.rooms.length === 0)
        || (SPEC.needs === 'durableRoom' && !acc.durableRoom)) {
      continue; // 시드가 없는 계정도 버린다(no-op 이터레이션 방지)
    }
    accounts.push(acc);
  }
  if (accounts.length === 0) {
    throw new Error('쓸 수 있는 계정이 0개 — BASE_URL·dev-login 게이트·DEV_LOGIN_KEY·시드를 확인할 것');
  }
  console.log(`setup 완료: 계정 ${accounts.length}/${names.length}개(닉네임 ${NICKNAMES ? __ENV.NICKNAMES : 'k6-user-N'}), `
    + `TARGET=${TARGET}, ${RPS} RPS / ${DURATION}`);
  return accounts;
}

function nicknames() {
  if (NICKNAMES) {
    return NICKNAMES.slice(0, USERS);
  }
  const names = [];
  for (let i = 1; i <= USERS; i += 1) {
    names.push(`k6-user-${i}`);
  }
  return names;
}

// 계정의 리포트를 그룹별로 몇 개씩 모은다. 그룹은 시드 풀(case_no 접두어 K6-P/D/M/I, 응답 필드는 report_no)이고,
// 시연용으로 case_no가 바뀐 시드면 리포트 상태로 나눈다. 제안 목록 대상은 제안이 2건 이상인 리포트(P 풀)로 고른다.
// 목록은 제안 단위로 펼쳐져 계정당 약 123행이라 100행씩 최대 2페이지를 본다.
function reportsOf(token) {
  const params = { headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup' } };
  const firstPage = http.get(`${BASE}/reports?page=1&size=100`, params);
  const cards = [].concat(json(firstPage, 'data.list') || []);
  if (json(firstPage, 'data.pagination.has_next') === true) {
    cards.push(...(json(http.get(`${BASE}/reports?page=2&size=100`, params), 'data.list') || []));
  }
  const groups = {};
  const proposalReports = [];
  const seen = {};
  for (const card of cards) {
    if (!card.report_id || seen[card.report_id]) {
      continue;
    }
    seen[card.report_id] = true;
    const reportNo = card.report_no;
    const group = typeof reportNo === 'string' && reportNo.startsWith('K6-') ? reportNo.charAt(3) : (card.status || 'X');
    groups[group] = groups[group] || [];
    if (groups[group].length < REPORTS_PER_POOL) {
      groups[group].push(card.report_id);
    }
    if (card.proposal_count >= 2 && proposalReports.length < REPORTS_PER_POOL) {
      proposalReports.push(card.report_id);
    }
  }
  const reports = [].concat(...Object.values(groups));
  return { reports, proposalReports: proposalReports.length ? proposalReports : reports };
}

function roomsOf(token) {
  // 채팅방 목록은 기본 20개씩 페이지로 나온다(#369). 시드 계정의 방은 38개라 한 번에 다 보려면 size를 올려야 한다.
  const res = http.get(`${BASE}/chats?size=50`,
    { headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup' } });
  const active = (json(res, 'data.rooms') || []).filter((room) => room.room_status === 'ACTIVE');
  // M 풀 방(메시지 41건)은 기본 페이지(30)를 넘겨 커서 조회가 실제로 도는 유일한 방이다. chat_messages는 이 방만 쓰고,
  // 못 찾으면 그 계정은 버린다(메시지 1건짜리 D 풀 방으로 대신하면 다른 것을 재게 된다).
  const durable = active.filter((room) => room.match_status === 'ACCEPTED');
  const rooms = durable.concat(active.filter((room) => room.match_status !== 'ACCEPTED'))
    .slice(0, ROOMS_PER_ACCOUNT)
    .map((room) => room.chat_room_id);
  return { rooms, durableRoom: durable.length ? durable[0].chat_room_id : null };
}

export function hit(accounts) {
  // VU 번호가 아니라 이터레이션 번호로 계정을 돌린다. VU 수가 계정 수보다 적어도 시드 전체에 고르게 퍼져야
  // 일부 계정 데이터만 캐시에 올라와 비현실적으로 빨라지는 일이 없다.
  const acc = accounts[exec.scenario.iterationInTest % accounts.length];
  const [method, path, body] = SPEC.call(acc);
  const res = http.request(method, `${BASE}${path}`, body === undefined ? null : body,
    { headers: authHeaders(acc.token), tags: { name: TARGET } });
  const expected = SPEC.expected || 200;
  check(res, { [`${TARGET} ${expected}`]: (r) => r.status === expected });
}

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
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
