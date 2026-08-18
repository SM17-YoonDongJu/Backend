#!/usr/bin/env bash
#
# NEEDS_REUPLOAD(OCR 품질 미달) 백/AI 통합 E2E 테스트.
# 일부러 깨진 파일을 업로드해 리포트를 생성하고, analysis_state가 NEEDS_REUPLOAD로 바뀌는지,
# 그에 따른 인앱 알림(REPORT_NEEDS_REUPLOAD)까지 실제로 뜨는지 끝까지 폴링해서 확인한다.
#
# 이 레포(Backend)만으로는 AI 워커(report-worker, 별도 이미지)의 처리를 관찰할 수 없으므로,
# 이 스크립트는 Backend 쪽 계약(업로드 → 리포트 생성 → 상태 조회 → 알림 조회)만 자동화한다.
# AI 워커가 실제로 얼마나 걸려 처리하는지는 환경에 달려 있어 폴링 타임아웃으로 기다린다.
#
# ── 인증 ─────────────────────────────────────────────────────────────────
# 배포된 dev 서버는 /auth/dev/login이 없다(로컬 전용, DevAuthController.java 주석 참고 — 배포
# 아티팩트에는 이 경로 자체가 없다). dev 서버를 테스트하려면 브라우저로 카카오/네이버 로그인 후
# 개발자도구 > Application/Storage > Cookies에서 access_token 값을 복사해 ACCESS_TOKEN으로 넘긴다.
# 로컬(SPRING_PROFILES_ACTIVE=local, app.dev-login.enabled=true)에서 Backend 단독 스모크를 돌릴
# 때만 DEV_LOGIN=true를 쓴다 — 이 경우 로컬엔 AI 워커가 없어 analysis_state가 절대 안 바뀐다.
#
# 사용법 (dev 서버, 실제 통합 테스트):
#   BASE_URL=https://dev-api.example.com \
#   ACCESS_TOKEN="<브라우저에서 복사한 access_token 쿠키 값>" \
#   DEVICE_TOKEN="<선택: FCM 디바이스 토큰>" \
#   ./scripts/needs-reupload-e2e-test.sh
#
# 사용법 (로컬, Backend 단독 스모크 — 상태 전이는 확인 불가):
#   BASE_URL=http://localhost:8080 DEV_LOGIN=true ./scripts/needs-reupload-e2e-test.sh
#
set -euo pipefail

command -v jq >/dev/null 2>&1 || { echo "jq가 필요합니다 (brew install jq)" >&2; exit 1; }

BASE_URL="${BASE_URL:?BASE_URL을 지정하세요 (예: https://dev-api.example.com)}"
ACCESS_TOKEN="${ACCESS_TOKEN:-}"
DEV_LOGIN="${DEV_LOGIN:-false}"
COOKIE_JAR="${COOKIE_JAR:-$(mktemp -t needs_reupload_cookies_XXXX)}"

FILE_PATH="${FILE_PATH:-}"
TRUNCATE_BYTES="${TRUNCATE_BYTES:-}"
CONTENT_TYPE="${CONTENT_TYPE:-application/pdf}"
PURPOSE="${PURPOSE:-report_document}"
DOC_NAME="${DOC_NAME:-}"
# report_type/file_type은 Backend에선 자유 문자열(enum 검증 없음) — "필수 문서 누락" 판정은 AI 워커
# 쪽 어휘에 달려 있다. fan-in이 기대대로 안 걸리면 ai_owner에게 허용 값 목록부터 확인할 것.
REPORT_TYPE="${REPORT_TYPE:-diagnosis}"
FILE_TYPE="${FILE_TYPE:-PDF}"

ACCIDENT_TYPE="${ACCIDENT_TYPE:-other}"
ACCIDENT_DATE="${ACCIDENT_DATE:-$(date +%F)}"

DEVICE_TOKEN="${DEVICE_TOKEN:-}"
DEVICE_PLATFORM="${DEVICE_PLATFORM:-ANDROID}"

STATUS_POLL_INTERVAL="${STATUS_POLL_INTERVAL:-5}"
STATUS_POLL_TIMEOUT="${STATUS_POLL_TIMEOUT:-600}"
NOTIF_POLL_INTERVAL="${NOTIF_POLL_INTERVAL:-10}"
NOTIF_POLL_TIMEOUT="${NOTIF_POLL_TIMEOUT:-180}"

log() { echo "[$(date +%T)] $*"; }

# ApiResponse 에러 형태({code, message} 존재)면 실패 처리
check_error() {
  local resp="$1" step="$2"
  local code
  code=$(echo "$resp" | jq -r '.code // empty')
  if [[ -n "$code" ]]; then
    echo "!! [$step] 실패: $(echo "$resp" | jq -r '.message // .code')" >&2
    echo "$resp" | jq . >&2
    exit 1
  fi
}

cleanup() {
  [[ -z "${KEEP_TMP:-}" ]] && rm -f "$COOKIE_JAR" "${GENERATED_FILE:-}"
}
trap cleanup EXIT

# ── 인증 옵션 구성 ───────────────────────────────────────────────────────
declare -a AUTH_OPTS
if [[ -n "$ACCESS_TOKEN" ]]; then
  log "제공된 ACCESS_TOKEN 쿠키로 인증"
  AUTH_OPTS=(-H "Cookie: access_token=${ACCESS_TOKEN}")
elif [[ "$DEV_LOGIN" == "true" ]]; then
  log "dev-login으로 로그인 시도 (local 프로파일 전용 — dev/prod 배포엔 이 경로가 없다)"
  LOGIN_RESP=$(curl -sS -c "$COOKIE_JAR" -X POST "$BASE_URL/auth/dev/login" \
    -H "Content-Type: application/json" -d '{}')
  check_error "$LOGIN_RESP" "dev-login"
  log "로그인 완료: $(echo "$LOGIN_RESP" | jq -c '.data')"
  AUTH_OPTS=(-b "$COOKIE_JAR")
else
  echo "ACCESS_TOKEN 또는 DEV_LOGIN=true 중 하나가 필요합니다." >&2
  exit 1
fi

# ── 0. (선택) 디바이스 토큰 등록 — FCM 수신 확인용 ─────────────────────
if [[ -n "$DEVICE_TOKEN" ]]; then
  log "디바이스 토큰 등록"
  RESP=$(curl -sS "${AUTH_OPTS[@]}" -X POST "$BASE_URL/users/me/device-tokens" \
    -H "Content-Type: application/json" \
    -d "{\"token\":\"${DEVICE_TOKEN}\",\"platform\":\"${DEVICE_PLATFORM}\"}")
  check_error "$RESP" "device-token"
  log "등록 완료"
fi

# ── 1. 테스트용 나쁜 파일 준비 ───────────────────────────────────────────
if [[ -z "$FILE_PATH" ]]; then
  GENERATED_FILE="$(mktemp -t needs_reupload_XXXX).pdf"
  # 매직바이트(%PDF-)는 살아있지만 stream/xref/trailer가 없는 구조적으로 깨진 PDF.
  # UploadService의 매직바이트 재검증(선언 Content-Type과 실제 시그니처 일치)은 통과하되
  # AI 워커의 OCR 파싱은 실패/저품질로 이어지길 기대하는 합성 파일이다.
  cat > "$GENERATED_FILE" <<'PDF_EOF'
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R>>endobj
4 0 obj<</Length 999999>>
stream
PDF_EOF
  FILE_PATH="$GENERATED_FILE"
  log "테스트용 합성 손상 PDF 생성: $FILE_PATH"
elif [[ -n "$TRUNCATE_BYTES" ]]; then
  GENERATED_FILE="$(mktemp -t needs_reupload_XXXX).pdf"
  head -c "$TRUNCATE_BYTES" "$FILE_PATH" > "$GENERATED_FILE"
  FILE_PATH="$GENERATED_FILE"
  log "원본을 ${TRUNCATE_BYTES}바이트로 잘라서 사용: $FILE_PATH"
else
  log "제공된 파일 그대로 사용: $FILE_PATH"
fi

[[ -z "$DOC_NAME" ]] && DOC_NAME="$(basename "$FILE_PATH")"

# ── 2. 업로드 (POST /uploads, multipart) ────────────────────────────────
log "업로드: $FILE_PATH (purpose=$PURPOSE, content-type=$CONTENT_TYPE)"
UPLOAD_RESP=$(curl -sS "${AUTH_OPTS[@]}" -X POST "$BASE_URL/uploads" \
  -F "file=@${FILE_PATH};type=${CONTENT_TYPE}" \
  -F "purpose=${PURPOSE}")
check_error "$UPLOAD_RESP" "upload"
S3_URL=$(echo "$UPLOAD_RESP" | jq -r '.data.s3_url')
log "업로드 완료: s3_url=$S3_URL"

# ── 3. 리포트 생성 (POST /reports) ──────────────────────────────────────
log "리포트 생성 (accident_type=$ACCIDENT_TYPE, accident_date=$ACCIDENT_DATE, document=$DOC_NAME)"
CREATE_BODY=$(jq -n \
  --arg accidentType "$ACCIDENT_TYPE" \
  --arg accidentDate "$ACCIDENT_DATE" \
  --arg s3Url "$S3_URL" \
  --arg name "$DOC_NAME" \
  --arg reportType "$REPORT_TYPE" \
  --arg fileType "$FILE_TYPE" \
  '{accident_type: $accidentType, accident_date: $accidentDate,
    documents: [{s3_url: $s3Url, name: $name, report_type: $reportType, file_type: $fileType}]}')
CREATE_RESP=$(curl -sS "${AUTH_OPTS[@]}" -X POST "$BASE_URL/reports" \
  -H "Content-Type: application/json" -d "$CREATE_BODY")
check_error "$CREATE_RESP" "create-report"
REPORT_ID=$(echo "$CREATE_RESP" | jq -r '.data.report_id')
log "리포트 생성 완료: report_id=$REPORT_ID (status=$(echo "$CREATE_RESP" | jq -r '.data.status'))"

# ── 4. analysis-status 폴링 ──────────────────────────────────────────────
log "analysis-status 폴링 시작 (최대 ${STATUS_POLL_TIMEOUT}s, ${STATUS_POLL_INTERVAL}s 간격)"
DEADLINE=$((SECONDS + STATUS_POLL_TIMEOUT))
STATE="PROCESSING"
STATUS_RESP=""
while (( SECONDS < DEADLINE )); do
  STATUS_RESP=$(curl -sS "${AUTH_OPTS[@]}" "$BASE_URL/reports/$REPORT_ID/analysis-status")
  check_error "$STATUS_RESP" "analysis-status"
  STATE=$(echo "$STATUS_RESP" | jq -r '.data.analysis_state')
  log "  analysis_state=$STATE"
  [[ "$STATE" != "PROCESSING" ]] && break
  sleep "$STATUS_POLL_INTERVAL"
done

echo "----- analysis-status 최종 응답 -----"
echo "$STATUS_RESP" | jq '.data'
echo "--------------------------------------"

if [[ "$STATE" == "PROCESSING" ]]; then
  log "!! 타임아웃까지 PROCESSING에 머물러 있음 — AI 워커가 아직 처리 안 했거나 SQS 소비가 안 되는 상태일 수 있음"
  log "   확인: report_id=$REPORT_ID 로 DB(core.reports.status), ai.ocr_job_failures, ai.ocr_results 직접 조회"
  exit 2
fi

if [[ "$STATE" == "NEEDS_REUPLOAD" ]]; then
  FAILED_DOCS=$(echo "$STATUS_RESP" | jq -c '.data.failed_documents')
  if [[ "$FAILED_DOCS" == "[]" ]]; then
    log "NEEDS_REUPLOAD는 떴지만 failed_documents가 비어있음 — 개별 문서 품질 게이트(ai.ocr_results) 경로일 가능성."
    log "  (dev에 ai.ocr_results GRANT가 아직 배포 전이면 항상 이렇게 degrade됨 — NeedsReuploadDocumentReader 주석 참고)"
  else
    log "문서가 특정됨(청구 fan-in 경로): $FAILED_DOCS"
  fi
elif [[ "$STATE" != "COMPLETED" ]]; then
  log "analysis_state=$STATE (NEEDS_REUPLOAD 아님) — BLOCKED/FAILED 케이스 확인 중이면 정상"
fi

# ── 5. 알림 폴링 (인앱 notifications) ────────────────────────────────────
declare -A STATE_TO_NOTIF=([NEEDS_REUPLOAD]="REPORT_NEEDS_REUPLOAD" [BLOCKED]="REPORT_BLOCKED" [FAILED]="ANALYSIS_FAILED")
EXPECTED_TYPE="${STATE_TO_NOTIF[$STATE]:-}"

if [[ -z "$EXPECTED_TYPE" ]]; then
  log "analysis_state=$STATE 는 알림 대상이 아님(COMPLETED 등) — 알림 폴링 생략"
else
  log "알림 폴링 시작 (기대 type=$EXPECTED_TYPE, 최대 ${NOTIF_POLL_TIMEOUT}s)"
  log "  스윕러 기본 주기가 60초(app.report.needs-reupload-sweep-interval-ms)라 즉시 안 떠도 정상"
  DEADLINE=$((SECONDS + NOTIF_POLL_TIMEOUT))
  FOUND=""
  while (( SECONDS < DEADLINE )); do
    NOTIF_RESP=$(curl -sS "${AUTH_OPTS[@]}" "$BASE_URL/users/me/notifications?page=0&size=5")
    check_error "$NOTIF_RESP" "notifications"
    FOUND=$(echo "$NOTIF_RESP" | jq -c --arg t "$EXPECTED_TYPE" \
      '[.data.items[] | select(.type == $t)] | first // empty')
    if [[ -n "$FOUND" ]]; then
      log "알림 발견: $FOUND"
      break
    fi
    sleep "$NOTIF_POLL_INTERVAL"
  done
  if [[ -z "$FOUND" ]]; then
    log "!! 타임아웃까지 인앱 알림($EXPECTED_TYPE)이 안 보임 — 스윕러가 안 돌았거나 needs_reupload_notified_at이 이미 찍혔을 수 있음"
  fi
fi

# ── 6. 요약 ───────────────────────────────────────────────────────────────
echo ""
echo "===== 요약 ====="
echo "report_id       : $REPORT_ID"
echo "analysis_state  : $STATE"
echo "in-app 알림      : $( [[ -n "${FOUND:-}" ]] && echo "확인됨" || echo "미확인/대상 아님" )"
echo ""
echo "FCM 실 푸시 여부는 이 스크립트로 확인 불가 — 서버 로그에서 아래 문자열 확인:"
echo '  "FirebaseApp 미구성(stub 모드): 푸시 발송을 건너뜁니다" 가 찍히면 dev에 FCM_SERVICE_ACCOUNT_PATH 미설정 상태'
echo ""
echo "DB로 교차 확인하려면:"
echo "  SELECT status, needs_reupload_notified_at, blocked_notified_at FROM core.reports WHERE id = '$REPORT_ID';"
echo "  SELECT * FROM ai.ocr_job_failures WHERE report_id = '$REPORT_ID';"
echo "  SELECT * FROM ai.ocr_results WHERE report_id = '$REPORT_ID';"
echo "  -- notifications엔 report_id 컬럼이 없음(Notification.java) — user_id·type·시각으로 대조"
echo "  SELECT * FROM core.notifications WHERE user_id = '<위 dev-login/로그인 응답의 user_id>' ORDER BY created_at DESC LIMIT 5;"
