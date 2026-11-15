// 고객(USER) 화면 단위 혼합 부하테스트 (#405).
// 이터레이션 1번 = 화면 진입 1번이다. 화면은 MIX 비중으로 고르고, 화면 안의 API는 http.batch로 동시에 보낸다.
// 화면별 API 묶음은 프론트(Frontend origin/dev)가 화면을 열 때 React Query로 한꺼번에 부르는 묶음을 그대로 옮겼다.
// 분석 대기 폴링(프론트가 분석 중인 리포트 상세를 5초마다 다시 부르는 것)은 polling scenario로 함께 돌린다.
// 한 번 실행 = RATE 1개. 계단은 RATE만 바꿔 다시 실행해서 밟는다(단계마다 rate 태그로 결과가 갈린다).
//
// 실행 (k6 인스턴스 /opt/k6 → 워커 NodePort 직결):
//   export DEV_LOGIN_KEY='<app/backend-dev의 DEV_LOGIN_SECRET>' BASE_URL=http://10.0.137.77:30080 K6_USERS=600 K6_STATSD_ENABLE_TAGS=true K6_STATSD_ADDR=127.0.0.1:8125
//   k6 run --out output-statsd --tag profile=user-journey -e RATE=10 -e DURATION=5m -e POLL_USERS=40 user-journey.k6.js
//
// env
// - RATE: 초당 화면 진입 수(기본 10). 기본 비중이면 화면 1번에 요청이 평균 3개쯤이라 RATE=10은 약 30 RPS다
// - DURATION: 유지 시간(기본 5m)
// - MAX_VUS: 화면 scenario VU 수(기본 200). 처음부터 전부 띄운다(아래 '계정 나눠 갖기')
// - POLL_USERS: 분석 결과를 기다리는 사용자 수(기본 0 = 끔). 1명이 5초에 1번 상세를 부른다(100명이면 약 20 RPS)
// - MIX: 화면 비중(기본 home:28,report_detail:18,chat_room:14,chat_talk:10,chat_list:8,reports_list:10,adjusters:6,proposals:4,mypage:2)
// - SESSION_START: 세션 첫 화면 비율(기본 0.2). 첫 화면이면 공통 레이아웃의 내 정보·알림 호출이 같이 나간다
//   (프론트가 30분 캐시하므로 그 뒤 화면에서는 나가지 않는다)
// - NICKNAMES: 로그인할 닉네임 목록 파일(한 줄에 하나, 스크립트 기준 상대 경로). 시드 닉네임이 바뀌어
//   k6-user-N이 없을 때만 쓴다. 없으면 k6-user-1~K6_USERS로 로그인한다
// - REFRESH_AFTER_MIN: 토큰을 받은 뒤 이 시간(기본 25분)이 지나면 다음 요청 전에 토큰을 갱신한다
//
// 토큰 갱신 — access 토큰은 30분(application.yml jwt.access-token-expiry)이라, setup에서 한 번 받은 토큰으로는 30분 넘는
// 실행이 후반에 전부 401이 된다. 그래서 받은 지 REFRESH_AFTER_MIN이 지난 계정은 다음 요청 전에 토큰을 갱신한다.
// - refresh 토큰이 있으면 POST /auth/reissue로 재발급한다(name=reissue). 프론트는 401을 받은 뒤 재발급하고 다시
//   보내는데(client.ts), 서버가 하는 일은 같고 401 한 번이 빠질 뿐이다. 재발급이 실패하면 dev-login으로 다시 로그인한다.
// - 지금 dev-login은 access 토큰만 주고 refresh 토큰을 만들지 않는다(DevLoginService). 그래서 k6 계정은 dev-login으로
//   다시 로그인해 갱신한다(name=relogin). 몇 번 갱신했는지는 token_renewals{how}로 센다.
//
// 계정 나눠 갖기 — 계정을 VU마다 겹치지 않게 나눠 주고(계정 i는 VU (i % 전체 VU 수)번이 맡는다), 각 VU는 자기 계정만
// 돌려 쓴다. 이유는 둘이다. 갱신을 계정마다 한 VU만 하게 해 갱신 요청이 계정 수만큼만 나가게 하고, 재발급(RTR)이 같은
// 계정에서 동시에 일어나지 않게 한다(이미 바뀐 옛 refresh 토큰이 다시 오면 서버가 탈취로 보고 토큰을 지운다).
// 화면 scenario의 VU를 처음부터 전부 띄워야 VU 번호가 1~(MAX_VUS + POLL_USERS)로 고정되고, k6가 쉬는 VU를 돌아가며
// 쓰므로 모든 계정이 고르게 쓰인다. 계정 수가 MAX_VUS + POLL_USERS보다 적으면 setup에서 멈춘다.
//
// 주의
// - 시드가 있어야 한다. dev-login은 없는 닉네임이면 빈 계정을 만들고, 리포트·방이 없는 계정은 setup에서 버린다.
//   setup 로그의 "계정 N/M"이 M보다 한참 작으면 시드부터 확인할 것
// - 데이터를 바꾸는 요청: 채팅방 읽음 처리(읽음 커서 갱신), 채팅 대화 화면의 메시지 전송(방마다 메시지가 쌓인다),
//   토큰 재발급. 시드는 소모하지 않는다
// - 폴링은 시드 리포트(분석 완료 상태)를 부른다. 서버가 하는 일은 분석 중인 리포트 상세와 같다
// - 사정사 시나리오와 동시에 돌리지 않는다
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const DEV_KEY = __ENV.DEV_LOGIN_KEY || ''; // 로컬은 시크릿이 없어 헤더 검증을 건너뛴다
const USERS = Number(__ENV.K6_USERS || 600);
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '5m';
const MAX_VUS = Number(__ENV.MAX_VUS || 200);
const POLL_USERS = Number(__ENV.POLL_USERS || 0);
const TOTAL_VUS = MAX_VUS + POLL_USERS; // 처음부터 전부 띄우므로 VU 번호는 1~TOTAL_VUS
const POLL_INTERVAL_SECONDS = 5; // 프론트 use-report-detail.ts의 ANALYSIS_POLL_MS
const SESSION_START = Number(__ENV.SESSION_START || 0.2);
const REFRESH_AFTER_MS = Number(__ENV.REFRESH_AFTER_MIN || 25) * 60 * 1000;
const DEFAULT_MIX = 'home:28,report_detail:18,chat_room:14,chat_talk:10,chat_list:8,reports_list:10,adjusters:6,'
  + 'proposals:4,mypage:2';
const MESSAGE = JSON.stringify({ content: 'k6 부하테스트 메시지입니다.' });
// setup 데이터는 VU마다 복사되므로 계정당 보관량을 작게 둔다(풀별 리포트 2건, 방 4개)
const REPORTS_PER_POOL = 2;
const ROOMS_PER_ACCOUNT = 4;
// open()은 init 단계에서만 부를 수 있다. setup만 쓰지만 VU마다 한 번씩 읽힌다(수 KB라 무시할 만하다).
const NICKNAMES = __ENV.NICKNAMES
  ? open(__ENV.NICKNAMES).split('\n').map((line) => line.trim()).filter(Boolean)
  : null;

// 엔드포인트 이름(name 태그). 단일 API 스크립트(user-single-api.k6.js)의 TARGET 이름과 맞췄다.
const ENDPOINTS = [
  'dashboard', 'reports_list', 'adjusters_list', 'adjuster_detail', 'chats_list', 'chat_room', 'chat_messages',
  'chat_read', 'chat_send', 'report_detail', 'analysis_metrics', 'proposals_list', 'me', 'notifications',
  'activity_summary', 'insurances', 'reissue', 'relogin',
];

// 화면 → 단계 목록. 단계 안의 호출은 동시에(http.batch), 단계끼리는 순서대로 보낸다.
const SCREENS = {
  // 홈: 대시보드 + 리포트 목록(분석 상태 표시용) + 추천 사정사 + 채팅 목록
  home: () => [[
    get('/users/me/dashboard', 'dashboard'),
    get('/reports', 'reports_list'),
    get('/adjusters?sort=rating&size=4', 'adjusters_list'),
    get('/chats', 'chats_list'),
  ]],
  // 리포트 상세: 상세 + 분석 근거 수치. 대상 리포트는 시드 풀(P·D·M·I)에서 섞어 고른다
  report_detail: (acc) => {
    const id = pick(acc.reports);
    return [[get(`/reports/${id}`, 'report_detail'), get(`/reports/${id}/analysis-metrics`, 'analysis_metrics')]];
  },
  // 채팅방 진입: 방 상세 + 방 목록 + 최근 메시지 30개 + 읽음 처리(마운트 때 함께 나간다)
  chat_room: (acc) => {
    const room = pick(acc.rooms);
    return [[
      get(`/chats/${room}`, 'chat_room'),
      get('/chats', 'chats_list'),
      get(`/chats/${room}/messages?size=30`, 'chat_messages'),
      post(`/chats/${room}/read`, 'chat_read'),
    ]];
  },
  // 채팅 대화: 메시지를 보내고, 전송이 끝나면 프론트가 메시지 목록과 방 목록을 다시 부른다(use-send-chat-message.ts)
  chat_talk: (acc) => {
    const room = pick(acc.rooms);
    return [
      [post(`/chats/${room}/messages`, 'chat_send', MESSAGE, 201)],
      [get(`/chats/${room}/messages?size=30`, 'chat_messages'), get('/chats', 'chats_list')],
    ];
  },
  chat_list: () => [[get('/chats', 'chats_list')]],
  // 내 리포트 목록: 첫 페이지, 30%는 "더보기"로 다음 페이지까지
  reports_list: () => {
    const steps = [[get('/reports?page=1', 'reports_list')]];
    if (Math.random() < 0.3) {
      steps.push([get('/reports?page=2', 'reports_list')]);
    }
    return steps;
  },
  // 사정사 찾기: 목록을 본 뒤 한 명을 연다
  adjusters: (acc, adjusterIds) => {
    const steps = [[get('/adjusters?page=1', 'adjusters_list')]];
    if (adjusterIds.length > 0) {
      steps.push([get(`/adjusters/${pick(adjusterIds)}`, 'adjuster_detail')]);
    }
    return steps;
  },
  // 제안 비교: 제안 목록 + 채팅 목록(제안과 연결된 방 찾기). 제안이 여러 개 달린 리포트(P 풀)가 대상이다
  proposals: (acc) => [[
    get(`/reports/${pick(acc.proposalReports)}/proposals?page=1&size=10`, 'proposals_list'),
    get('/chats', 'chats_list'),
  ]],
  mypage: () => [[
    get('/users/me', 'me'),
    get('/users/me/activity-summary', 'activity_summary'),
    get('/reports', 'reports_list'),
    get('/users/me/insurances', 'insurances'),
  ]],
};

// 세션 첫 화면이면 공통 레이아웃이 같이 부르는 호출
const LAYOUT_CALLS = [get('/users/me', 'me'), get('/users/me/notifications', 'notifications')];

const MIX = parseMix(__ENV.MIX || DEFAULT_MIX);
const screenDuration = new Trend('screen_duration', true);
const tokenRenewals = new Counter('token_renewals');

export const options = {
  scenarios: buildScenarios(),
  thresholds: buildThresholds(),
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '15m',
  discardResponseBodies: true,
};

function buildScenarios() {
  const scenarios = {
    screens: {
      executor: 'constant-arrival-rate', // 개방형 — 응답이 느려져도 도착률을 유지해 포화를 드러낸다
      exec: 'screen',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // 계정을 VU마다 나눠 갖기 때문에 VU를 처음부터 전부 띄운다(맨 위 '계정 나눠 갖기')
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
      tags: { phase: 'main', rate: String(RATE) },
    },
  };
  if (POLL_USERS > 0) {
    scenarios.polling = {
      executor: 'constant-vus', // 대기 사용자 1명 = VU 1개, 5초마다 다시 부른다
      exec: 'poll',
      vus: POLL_USERS,
      duration: DURATION,
      tags: { phase: 'main', rate: String(RATE) },
    };
  }
  return scenarios;
}

// threshold는 판정보다 요약 표시용이다 — k6는 threshold로 건 하위 지표만 요약에 따로 보여 준다.
// phase:main으로 걸어야 setup 요청(로그인·ID 확보)이 빠진다.
function buildThresholds() {
  const thresholds = {
    'http_req_failed{phase:main}': ['rate<0.01'],
  };
  for (const name of Object.keys(SCREENS)) {
    thresholds[`screen_duration{screen:${name}}`] = ['p(95)<1500'];
  }
  for (const name of ENDPOINTS) {
    thresholds[`http_req_duration{name:${name}}`] = ['p(95)<1000'];
  }
  return thresholds;
}

export function setup() {
  const names = nicknames();
  const accounts = [];
  let dropped = 0;
  for (const nickname of names) {
    const session = login(nickname, { phase: 'setup', name: 'setup_login' });
    if (!session) {
      dropped += 1;
      continue; // 무인증 계정이 섞이면 1ms 401로 이터레이션을 삼켜 측정이 왜곡된다(#297 원인 3)
    }
    const found = discover(session.access);
    if (found.reports.length === 0 || found.rooms.length === 0) {
      dropped += 1;
      continue; // 시드가 없는 계정(빈 계정)은 화면 대부분이 빈 응답이라 버린다
    }
    accounts.push({ nickname, token: session.access, refresh: session.refresh, at: Date.now(), ...found });
  }
  if (accounts.length === 0) {
    throw new Error('쓸 수 있는 계정이 0개 — BASE_URL·dev-login 게이트·DEV_LOGIN_KEY·시드를 확인할 것');
  }
  if (accounts.length < TOTAL_VUS) {
    throw new Error(`계정 ${accounts.length}개로는 VU ${TOTAL_VUS}개(MAX_VUS ${MAX_VUS} + POLL_USERS ${POLL_USERS})에 `
      + '계정을 하나씩 나눠 줄 수 없다 — MAX_VUS나 POLL_USERS를 줄일 것');
  }
  const adjusterIds = discoverAdjusters(accounts[0].token);
  console.log(`setup 완료: 계정 ${accounts.length}/${names.length}개(버림 ${dropped}, 닉네임 ${NICKNAMES ? __ENV.NICKNAMES : 'k6-user-N'}), `
    + `사정사 ${adjusterIds.length}명, 화면 ${RATE}/s(VU ${MAX_VUS}), 폴링 ${POLL_USERS}명, ${DURATION}, `
    + `재발급 ${REFRESH_AFTER_MS / 60000}분 뒤, 비중 ${__ENV.MIX || DEFAULT_MIX}`);
  return { accounts, adjusterIds };
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

// dev-login으로 토큰을 받는다. 쿠키로 오지만 jar는 쓰지 않는다(아래 authHeaders 주석).
// refresh 토큰은 dev-login이 주지 않으면 null이다(맨 위 '토큰 갱신').
function login(nickname, tags) {
  const res = http.post(`${BASE}/auth/dev/login`, JSON.stringify({ nickname }), {
    headers: { 'Content-Type': 'application/json', 'X-Dev-Login-Key': DEV_KEY },
    responseType: 'text',
    tags,
  });
  const access = (res.cookies.access_token || [])[0];
  const refresh = (res.cookies.refresh_token || [])[0];
  return access ? { access: access.value, refresh: refresh ? refresh.value : null } : null;
}

// 계정의 리포트를 그룹별로 몇 개씩, 방을 메시지 많은 M 방 우선으로 모은다. 그룹은 시드 풀(case_no 접두어 K6-P/D/M/I,
// 응답 필드는 report_no)이고, 시연용으로 case_no가 바뀐 시드면 리포트 상태로 나눈다. 제안 비교 대상은 제안이 2건 이상인
// 리포트(P 풀)로 고른다.
function discover(token) {
  const params = { headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup', name: 'setup_discover' } };
  const [firstPage, chats] = http.batch([
    { method: 'GET', url: `${BASE}/reports?page=1&size=100`, params },
    { method: 'GET', url: `${BASE}/chats?size=50`, params },
  ]);
  const cards = [].concat(json(firstPage, 'data.list') || []);
  if (json(firstPage, 'data.pagination.has_next') === true) {
    cards.push(...(json(http.get(`${BASE}/reports?page=2&size=100`, params), 'data.list') || []));
  }
  const groups = {};
  const proposalReports = [];
  const seen = {};
  for (const card of cards) {
    if (!card.report_id || seen[card.report_id]) {
      continue; // 목록은 제안 단위로 펼쳐져 같은 리포트가 여러 행으로 나온다
    }
    seen[card.report_id] = true;
    const group = groupOf(card);
    groups[group] = groups[group] || [];
    if (groups[group].length < REPORTS_PER_POOL) {
      groups[group].push(card.report_id);
    }
    if (card.proposal_count >= 2 && proposalReports.length < REPORTS_PER_POOL) {
      proposalReports.push(card.report_id);
    }
  }
  const reports = [].concat(...Object.values(groups));
  const activeRooms = (json(chats, 'data.rooms') || []).filter((room) => room.room_status === 'ACTIVE');
  const rooms = activeRooms.filter((room) => room.match_status === 'ACCEPTED')
    .concat(activeRooms.filter((room) => room.match_status !== 'ACCEPTED'))
    .slice(0, ROOMS_PER_ACCOUNT)
    .map((room) => room.chat_room_id);
  return { reports, proposalReports: proposalReports.length ? proposalReports : reports, rooms };
}

function discoverAdjusters(token) {
  const res = http.get(`${BASE}/adjusters?sort=rating&page=1&size=50`, {
    headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup', name: 'setup_discover' },
  });
  return (json(res, 'data.list') || []).map((item) => item.adjuster_id).filter(Boolean);
}

// ---- VU별 상태: 이 VU가 맡은 계정과 그 계정들의 토큰. 다른 VU와 공유하지 않는다 ----
let owned = null;
let cursor = 0;
const sessions = {};

function myAccounts(data) {
  if (owned === null) {
    const slot = (exec.vu.idInTest - 1) % TOTAL_VUS;
    owned = data.accounts.filter((acc, i) => i % TOTAL_VUS === slot);
  }
  return owned;
}

function nextAccount(data) {
  const mine = myAccounts(data);
  const acc = mine[cursor % mine.length];
  cursor += 1;
  return acc;
}

// 계정의 access 토큰. 받은 지 REFRESH_AFTER_MIN이 지났으면 먼저 갱신한다.
function accessToken(acc) {
  let session = sessions[acc.nickname];
  if (!session) {
    session = { access: acc.token, refresh: acc.refresh, at: acc.at };
    sessions[acc.nickname] = session;
  }
  if (Date.now() - session.at > REFRESH_AFTER_MS) {
    renew(acc, session);
  }
  return session.access;
}

function renew(acc, session) {
  if (session.refresh && reissue(session)) {
    tokenRenewals.add(1, { how: 'reissue' });
    return;
  }
  const fresh = login(acc.nickname, { name: 'relogin', screen: 'session' });
  const ok = check(fresh, { 'relogin 200': (value) => value !== null }, { screen: 'session' });
  if (ok) {
    session.access = fresh.access;
    session.refresh = fresh.refresh;
    session.at = Date.now();
    tokenRenewals.add(1, { how: 'relogin' });
  }
}

// POST /auth/reissue는 refresh 쿠키로만 동작한다(JwtFilter가 /auth/**를 건너뛴다). 새 access·refresh가 쿠키로 온다.
function reissue(session) {
  const res = http.post(`${BASE}/auth/reissue`, null, {
    headers: { Cookie: `refresh_token=${session.refresh}` },
    tags: { name: 'reissue', screen: 'session' },
  });
  const access = (res.cookies.access_token || [])[0];
  const refresh = (res.cookies.refresh_token || [])[0];
  const ok = check(res, { 'reissue 200': (r) => r.status === 200 && Boolean(access && refresh) },
    { screen: 'session' });
  if (ok) {
    session.access = access.value;
    session.refresh = refresh.value;
    session.at = Date.now();
  }
  return ok;
}

export function screen(data) {
  const acc = nextAccount(data);
  const token = accessToken(acc);
  const name = pickScreen();
  const steps = SCREENS[name](acc, data.adjusterIds);
  if (Math.random() < SESSION_START) {
    const names = steps[0].map((call) => call.name);
    steps[0] = steps[0].concat(LAYOUT_CALLS.filter((call) => !names.includes(call.name))); // 같은 쿼리는 프론트도 한 번만
  }
  const started = Date.now();
  for (const step of steps) {
    const responses = http.batch(step.map((call) => toRequest(call, token, name)));
    for (let i = 0; i < responses.length; i += 1) {
      const expected = step[i].expect;
      check(responses[i], { [`${step[i].name} ${expected}`]: (res) => res.status === expected }, { screen: name });
    }
  }
  screenDuration.add(Date.now() - started, { screen: name });
}

export function poll(data) {
  // 대기 사용자 1명 = VU 1개. 자기 계정의 리포트 하나를 띄워 두고 5초마다 상세를 다시 부른다
  const acc = myAccounts(data)[0];
  const token = accessToken(acc);
  const id = acc.reports[exec.vu.idInTest % acc.reports.length];
  const res = http.get(`${BASE}/reports/${id}`, {
    headers: authHeaders(token), tags: { name: 'report_detail', screen: 'analysis_wait' },
  });
  check(res, { 'report_detail 200': (r) => r.status === 200 }, { screen: 'analysis_wait' });
  sleep(POLL_INTERVAL_SECONDS);
}

function get(path, name) {
  return { method: 'GET', path, name, body: null, expect: 200 };
}

function post(path, name, body, expect) {
  return { method: 'POST', path, name, body: body || '', expect: expect || 200 };
}

function toRequest(call, token, screenName) {
  return {
    method: call.method,
    url: `${BASE}${call.path}`,
    body: call.body,
    params: { headers: authHeaders(token), tags: { name: call.name, screen: screenName } },
  };
}

function authHeaders(token) {
  // 쿠키 jar를 거치지 않고 헤더로 직접 싣는다. dev의 access_token은 Secure라 평문 http로는 jar가 되돌려
  // 보내지 않고, jar 자체도 이터레이션마다 초기화된다(#297 원인 1·2).
  return { 'Content-Type': 'application/json', Cookie: `access_token=${token}` };
}

function groupOf(card) {
  // 시드 case_no 형식: K6-{P|D|M|I}-{userSeq:04d}-{idx:03d}. 접두어가 없으면(시연용으로 바뀐 시드 등) 리포트 상태로 나눈다
  const reportNo = card.report_no;
  if (typeof reportNo === 'string' && reportNo.startsWith('K6-')) {
    return reportNo.charAt(3);
  }
  return card.status || 'X';
}

function parseMix(text) {
  const entries = [];
  let total = 0;
  for (const part of text.split(',')) {
    const [name, weight] = part.split(':').map((value) => value.trim());
    if (!SCREENS[name]) {
      throw new Error(`MIX에 알 수 없는 화면: ${name} (${Object.keys(SCREENS).join(', ')})`);
    }
    const value = Number(weight);
    if (value > 0) {
      total += value;
      entries.push({ name, cumulative: total });
    }
  }
  if (entries.length === 0) {
    throw new Error(`MIX가 비었다: ${text}`);
  }
  return { entries, total };
}

function pickScreen() {
  const roll = Math.random() * MIX.total;
  for (const entry of MIX.entries) {
    if (roll < entry.cumulative) {
      return entry.name;
    }
  }
  return MIX.entries[MIX.entries.length - 1].name;
}

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function json(res, path) {
  try {
    return res.json(path);
  } catch (err) {
    return null; // 비-JSON 응답(Tomcat 400 HTML 등)은 없는 값으로 취급
  }
}
