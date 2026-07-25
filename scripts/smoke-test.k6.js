// k6 기반 smoke test — 최소 부하로 헬스체크·메트릭 노출 안정성 확인.
// 비즈니스 포트(BASE_URL)와 actuator 관리 포트(MGMT_URL)를 분리한다(dev/prod은 actuator=9292).
// 실행:
//   로컬:    k6 run -e BASE_URL=http://localhost:8080 scripts/smoke-test.k6.js
//   dev/prod: k6 run -e BASE_URL=http://backend:8080 -e MGMT_URL=http://backend:9292 scripts/smoke-test.k6.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MGMT_URL = __ENV.MGMT_URL || BASE_URL; // actuator 대상(관리 포트 분리 시 별도 지정)

export const options = {
  vus: 2,              // 가상 사용자 2명
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],     // 실패율 1% 미만
    http_req_duration: ['p(95)<500'],   // 95%ile 응답 500ms 미만
  },
};

export default function () {
  const health = http.get(`${MGMT_URL}/actuator/health`);
  check(health, {
    'health 200': (r) => r.status === 200,
    'health UP': (r) => r.body && r.body.includes('"status":"UP"'),
  });

  const readiness = http.get(`${MGMT_URL}/actuator/health/readiness`);
  check(readiness, {
    'readiness 200': (r) => r.status === 200,
  });

  // Prometheus 메트릭 노출 (#89) — 200 + 핵심 메트릭 패밀리 존재
  const metrics = http.get(`${MGMT_URL}/actuator/prometheus`);
  check(metrics, {
    'prometheus 200': (r) => r.status === 200,
    'jvm 메트릭': (r) => r.body && r.body.includes('jvm_memory_used_bytes'),
    'hikari 메트릭': (r) => r.body && r.body.includes('hikaricp_connections'),
    'application 태그': (r) => r.body && r.body.includes('application="brbs-backend"'),
  });

  sleep(1);
}
