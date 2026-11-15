// 고객(USER) 화면 단위 혼합 부하테스트 (#405).
// 이터레이션 1번 = 화면 진입 1번이다. 화면은 MIX 비중으로 고르고, 화면 안의 API는 http.batch로 동시에 보낸다.
// 화면별 API 묶음은 프론트(Frontend origin/dev)가 화면을 열 때 React Query로 한꺼번에 부르는 묶음을 그대로 옮겼다.
// 분석 대기 폴링(프론트가 분석 중인 리포트 상세를 5초마다 다시 부르는 것)은 polling scenario로 함께 돌린다.
// 한 번 실행 = RATE 1개. 계단은 RATE만 바꿔 다시 실행해서 밟는다(단계마다 rate 태그로 결과가 갈린다).
//
// 실행 (k6 인스턴스 /opt/k6 → 워커 NodePort 직결):
//   export DEV_LOGIN_KEY='<app/backend-dev의 DEV_LOGIN_SECRET>' BASE_URL=http://10.0.137.77:30080 K6_USERS=600 K6_STATSD_ENABLE_TAGS=true K6_STATSD_ADDR=127.0.0.1:8125
//   k6 run --out output-statsd --tag profile=user-journey -e RATE=10 -e DURATION=5m -e POLL_USERS=50 user-journey.k6.js
//
// env
// - RATE: 초당 화면 진입 수(기본 10). 기본 비중이면 화면 1번에 요청이 평균 2.8개쯤이라 RATE=10은 약 28 RPS다
// - DURATION: 유지 시간(기본 5m), MAX_VUS: 화면 scenario VU 상한(기본 300)
// - POLL_USERS: 분석 결과를 기다리는 사용자 수(기본 0 = 끔). 1명이 5초에 1번 상세를 부른다(100명이면 약 20 RPS)
// - MIX: 화면 비중(기본 home:30,report_detail:20,chat_room:15,chat_list:10,reports_list:10,adjusters:7,proposals:5,mypage:3)
// - SESSION_START: 세션 첫 화면 비율(기본 0.2). 첫 화면이면 공통 레이아웃의 내 정보·알림 호출이 같이 나간다
//   (프론트가 30분 캐시하므로 그 뒤 화면에서는 나가지 않는다)
//
// 주의
// - 시드(k6-user-1~K6_USERS)가 있어야 한다. dev-login은 없는 닉네임이면 빈 계정을 만들고, 리포트·방이 없는 계정은
//   setup에서 버린다. setup 로그의 "계정 N/600"이 600보다 한참 작으면 시드부터 확인할 것
// - 데이터를 바꾸는 요청은 채팅방 진입 화면의 읽음 처리(POST /chats/{id}/read, 읽음 커서 갱신)뿐이다. 시드는 소모하지 않는다
// - 폴링은 시드 리포트(분석 완료 상태)를 부른다. 서버가 하는 일은 분석 중인 리포트 상세와 같다
// - 사정사 시나리오와 동시에 돌리지 않는다
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const DEV_KEY = __ENV.DEV_LOGIN_KEY || ''; // 로컬은 시크릿이 없어 헤더 검증을 건너뛴다
const USERS = Number(__ENV.K6_USERS || 600);
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '5m';
const MAX_VUS = Number(__ENV.MAX_VUS || 300);
const POLL_USERS = Number(__ENV.POLL_USERS || 0);
const POLL_INTERVAL_SECONDS = 5; // 프론트 use-report-detail.ts의 ANALYSIS_POLL_MS
const SESSION_START = Number(__ENV.SESSION_START || 0.2);
const DEFAULT_MIX = 'home:30,report_detail:20,chat_room:15,chat_list:10,reports_list:10,adjusters:7,proposals:5,mypage:3';
// setup 데이터는 VU마다 복사되므로 계정당 보관량을 작게 둔다(풀별 리포트 2건, 방 4개)
const REPORTS_PER_POOL = 2;
const ROOMS_PER_ACCOUNT = 4;

// 엔드포인트 이름(name 태그). 단일 API 스크립트(user-single-api.k6.js)의 TARGET 이름과 맞췄다.
const ENDPOINTS = [
  'dashboard', 'reports_list', 'adjusters_list', 'adjuster_detail', 'chats_list', 'chat_room', 'chat_messages',
  'chat_read', 'report_detail', 'analysis_metrics', 'proposals_list', 'me', 'notifications', 'activity_summary',
  'insurances',
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
  // 제안 비교: 제안 목록 + 채팅 목록(제안과 연결된 방 찾기). P 풀 리포트가 대상이다
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
      preAllocatedVUs: Math.min(RATE * 4, 200),
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
  const accounts = [];
  let dropped = 0;
  for (let i = 1; i <= USERS; i += 1) {
    const token = login(`k6-user-${i}`);
    if (!token) {
      dropped += 1;
      continue; // 무인증 계정이 섞이면 1ms 401로 이터레이션을 삼켜 측정이 왜곡된다(#297 원인 3)
    }
    const found = discover(token);
    if (found.reports.length === 0 || found.rooms.length === 0) {
      dropped += 1;
      continue; // 시드가 없는 계정(빈 계정)은 화면 대부분이 빈 응답이라 버린다
    }
    accounts.push({ token, ...found });
  }
  if (accounts.length === 0) {
    throw new Error('쓸 수 있는 계정이 0개 — BASE_URL·dev-login 게이트·DEV_LOGIN_KEY·시드를 확인할 것');
  }
  const adjusterIds = discoverAdjusters(accounts[0].token);
  console.log(`setup 완료: 계정 ${accounts.length}/${USERS}개(버림 ${dropped}), 사정사 ${adjusterIds.length}명, `
    + `화면 ${RATE}/s, 폴링 ${POLL_USERS}명, ${DURATION}, 비중 ${__ENV.MIX || DEFAULT_MIX}`);
  return { accounts, adjusterIds };
}

function login(nickname) {
  const res = http.post(`${BASE}/auth/dev/login`, JSON.stringify({ nickname }), {
    headers: { 'Content-Type': 'application/json', 'X-Dev-Login-Key': DEV_KEY },
    responseType: 'text',
    tags: { phase: 'setup', name: 'setup_login' },
  });
  const cookie = (res.cookies.access_token || [])[0];
  return cookie ? cookie.value : null;
}

// 계정의 리포트를 시드 풀별로(case_no 접두어 K6-P/D/M/I, 응답 필드는 report_no), 방을 메시지 많은 M 방 우선으로 모은다.
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
  const pools = {};
  const seen = {};
  for (const card of cards) {
    if (!card.report_id || seen[card.report_id]) {
      continue; // 목록은 제안 단위로 펼쳐져 같은 리포트가 여러 행으로 나온다
    }
    seen[card.report_id] = true;
    const pool = poolOf(card.report_no);
    pools[pool] = pools[pool] || [];
    if (pools[pool].length < REPORTS_PER_POOL) {
      pools[pool].push(card.report_id);
    }
  }
  const reports = [].concat(...Object.values(pools));
  const activeRooms = (json(chats, 'data.rooms') || []).filter((room) => room.room_status === 'ACTIVE');
  const rooms = activeRooms.filter((room) => room.match_status === 'ACCEPTED')
    .concat(activeRooms.filter((room) => room.match_status !== 'ACCEPTED'))
    .slice(0, ROOMS_PER_ACCOUNT)
    .map((room) => room.chat_room_id);
  return { reports, proposalReports: pools.P && pools.P.length ? pools.P : reports, rooms };
}

function discoverAdjusters(token) {
  const res = http.get(`${BASE}/adjusters?sort=rating&page=1&size=50`, {
    headers: authHeaders(token), responseType: 'text', tags: { phase: 'setup', name: 'setup_discover' },
  });
  return (json(res, 'data.list') || []).map((item) => item.adjuster_id).filter(Boolean);
}

export function screen(data) {
  // VU 번호가 아니라 이터레이션 번호로 계정을 돌린다 — VU 수가 계정 수보다 적어도 시드 전체에 고르게 퍼지게
  const acc = data.accounts[exec.scenario.iterationInTest % data.accounts.length];
  const name = pickScreen();
  const steps = SCREENS[name](acc, data.adjusterIds);
  if (Math.random() < SESSION_START) {
    const names = steps[0].map((call) => call.name);
    steps[0] = steps[0].concat(LAYOUT_CALLS.filter((call) => !names.includes(call.name))); // 같은 쿼리는 프론트도 한 번만
  }
  const started = Date.now();
  for (const step of steps) {
    const responses = http.batch(step.map((call) => toRequest(call, acc.token, name)));
    for (let i = 0; i < responses.length; i += 1) {
      check(responses[i], { [`${step[i].name} 200`]: (res) => res.status === 200 }, { screen: name });
    }
  }
  screenDuration.add(Date.now() - started, { screen: name });
}

export function poll(data) {
  // VU마다 계정과 리포트를 하나 정해 두고(같은 화면을 띄워 둔 사용자) 5초마다 상세를 다시 부른다
  const vu = exec.vu.idInTest - 1;
  const acc = data.accounts[vu % data.accounts.length];
  const id = acc.reports[vu % acc.reports.length];
  const res = http.get(`${BASE}/reports/${id}`, {
    headers: authHeaders(acc.token), tags: { name: 'report_detail', screen: 'analysis_wait' },
  });
  check(res, { 'report_detail 200': (r) => r.status === 200 }, { screen: 'analysis_wait' });
  sleep(POLL_INTERVAL_SECONDS);
}

function get(path, name) {
  return { method: 'GET', path, name };
}

function post(path, name) {
  return { method: 'POST', path, name };
}

function toRequest(call, token, screenName) {
  return {
    method: call.method,
    url: `${BASE}${call.path}`,
    body: call.method === 'POST' ? '' : null,
    params: { headers: authHeaders(token), tags: { name: call.name, screen: screenName } },
  };
}

function authHeaders(token) {
  // 쿠키 jar를 거치지 않고 헤더로 직접 싣는다. dev의 access_token은 Secure라 평문 http로는 jar가 되돌려
  // 보내지 않고, jar 자체도 이터레이션마다 초기화된다(#297 원인 1·2).
  return { 'Content-Type': 'application/json', Cookie: `access_token=${token}` };
}

function poolOf(reportNo) {
  // 시드 case_no 형식: K6-{P|D|M|I}-{userSeq:04d}-{idx:03d}. 그 밖(직접 만든 리포트 등)은 X로 모은다
  return typeof reportNo === 'string' && reportNo.startsWith('K6-') ? reportNo.charAt(3) : 'X';
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
