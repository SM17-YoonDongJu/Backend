#!/usr/bin/env bash
#
# 배포 후 기본 동작 확인용 smoke test (curl 기반).
#
# 비즈니스 포트(BASE_URL)와 actuator 관리 포트(MGMT_URL)를 분리한다.
#   - 로컬/테스트: 관리 포트 미분리 → MGMT_URL 기본값 = BASE_URL (둘 다 8080)
#   - dev/prod: actuator를 9292로 격리 → MGMT_URL을 관리 포트로 지정
#
# 사용법:
#   # 로컬 (actuator·비즈니스 동일 포트)
#   BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh
#
#   # dev/prod (actuator=9292 관리 포트). dev는 9292를 호스트에 게시하므로 호스트에서 바로 실행:
#   BASE_URL=http://localhost:8080 MGMT_URL=http://localhost:9292 ./scripts/smoke-test.sh
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
# actuator 엔드포인트 대상. 관리 포트를 분리한 환경(dev/prod)에선 MGMT_URL을 별도로 준다.
MGMT_URL="${MGMT_URL:-$BASE_URL}"
FAILED=0

# 상태코드 검사
check() {
  local name="$1"
  local url="$2"
  local expect="${3:-200}"

  local code
  code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 10 "$url" || echo "000")
  if [ "$code" = "$expect" ]; then
    echo "  PASS  $name ($code) $url"
  else
    echo "  FAIL  $name (got $code, want $expect) $url"
    FAILED=1
  fi
}

# 응답 본문에 특정 문자열이 포함되는지 검사
check_body() {
  local name="$1"
  local url="$2"
  local needle="$3"

  if curl -sS --max-time 10 "$url" | grep -q -- "$needle"; then
    echo "  PASS  $name"
  else
    echo "  FAIL  $name (missing: $needle) $url"
    FAILED=1
  fi
}

echo "== Smoke test =="
echo "   business: $BASE_URL"
echo "   actuator: $MGMT_URL"

# 1) 헬스체크 (liveness / readiness) — 관리 포트
check "actuator/health"            "$MGMT_URL/actuator/health"
check "actuator/health/liveness"   "$MGMT_URL/actuator/health/liveness"
check "actuator/health/readiness"  "$MGMT_URL/actuator/health/readiness"
check_body "health status == UP"   "$MGMT_URL/actuator/health" '"status":"UP"'

# 2) 비즈니스 포트를 한 번 warm — http_server_requests 메트릭은 메인 서버에 트래픽이 있어야 노출된다.
#    (응답 코드는 무관: 404/401도 WebMvcMetricsFilter가 계측한다)
curl -sS -o /dev/null --max-time 10 "$BASE_URL/" || true

# 3) Prometheus 메트릭 노출 (#89) — 엔드포인트 200 + 핵심 메트릭 패밀리 존재
check "actuator/prometheus"        "$MGMT_URL/actuator/prometheus"
check_body "JVM 메트릭"            "$MGMT_URL/actuator/prometheus" 'jvm_memory_used_bytes'
check_body "HTTP 서버 메트릭"      "$MGMT_URL/actuator/prometheus" 'http_server_requests_seconds'
check_body "HikariCP 풀 메트릭"    "$MGMT_URL/actuator/prometheus" 'hikaricp_connections'
check_body "application 공통 태그"  "$MGMT_URL/actuator/prometheus" 'application="brbs-backend"'

# 4) 인증 없이 보호 리소스 접근 시 정상 라우팅 (404/401 등 5xx 아님)
#    엔드포인트가 늘어나면 여기에 추가한다.

if [ "$FAILED" -eq 0 ]; then
  echo "== ALL SMOKE TESTS PASSED =="
  exit 0
else
  echo "== SMOKE TESTS FAILED =="
  exit 1
fi
