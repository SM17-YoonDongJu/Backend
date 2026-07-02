#!/usr/bin/env bash
#
# 배포 후 기본 동작 확인용 smoke test (curl 기반).
# 사용법: BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh
#
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
FAILED=0

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

echo "== Smoke test against $BASE_URL =="

# 1) 헬스체크 (liveness / readiness)
check "actuator/health"            "$BASE_URL/actuator/health"
check "actuator/health/liveness"   "$BASE_URL/actuator/health/liveness"
check "actuator/health/readiness"  "$BASE_URL/actuator/health/readiness"

# 2) health 응답이 UP 인지 본문 확인
if curl -sS --max-time 10 "$BASE_URL/actuator/health" | grep -q '"status":"UP"'; then
  echo "  PASS  health status == UP"
else
  echo "  FAIL  health status != UP"
  FAILED=1
fi

# 3) 인증 없이 보호 리소스 접근 시 정상 라우팅 (404/401 등 5xx 아님)
#    엔드포인트가 늘어나면 여기에 추가한다.

if [ "$FAILED" -eq 0 ]; then
  echo "== ALL SMOKE TESTS PASSED =="
  exit 0
else
  echo "== SMOKE TESTS FAILED =="
  exit 1
fi
