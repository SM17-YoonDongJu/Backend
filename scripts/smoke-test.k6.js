// k6 기반 smoke test — 최소 부하로 헬스체크 안정성 확인.
// 실행: k6 run -e BASE_URL=http://localhost:8080 scripts/smoke-test.k6.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  vus: 2,              // 가상 사용자 2명
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],     // 실패율 1% 미만
    http_req_duration: ['p(95)<500'],   // 95%ile 응답 500ms 미만
  },
};

export default function () {
  const health = http.get(`${BASE_URL}/actuator/health`);
  check(health, {
    'health 200': (r) => r.status === 200,
    'health UP': (r) => r.body && r.body.includes('"status":"UP"'),
  });

  const readiness = http.get(`${BASE_URL}/actuator/health/readiness`);
  check(readiness, {
    'readiness 200': (r) => r.status === 200,
  });

  sleep(1);
}
