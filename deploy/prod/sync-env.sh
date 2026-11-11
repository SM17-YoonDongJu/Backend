#!/usr/bin/env bash
# prod EC2의 .env.prod를 새로 쓴다. 설정은 Parameter Store(/brbs/prod/env/, String), 시크릿은 Secrets Manager
# (prod/backend)에서 받는다. backend가 읽는 FCM 키 파일과 report가 마운트하는 RDS CA 번들도 함께 준비한다.
# FCM 키(prod/backend의 FCM_SERVICE_ACCOUNT_JSON)는 선택이다 — 있으면 파일을 배치하고 .env.prod에
# FCM_SERVICE_ACCOUNT_PATH를 넣어 실발송, 없으면 넣지 않아 backend가 stub 모드(푸시 미발송)로 기동한다.
# 이 디렉터리(서버 ~/backend)에서 root로 실행한다(SSM RunShellScript는 root, 직접 접속했다면 sudo bash sync-env.sh).
#
# 서버의 .env.prod는 언제든 이 스크립트로 다시 만들 수 있는 사본이다 — 직접 고치지 말고 원본을 고친 뒤 다시 돌린다.
# 레포가 public이라 설정값도 레포에 두지 않는다.
#
# 시크릿을 Parameter Store SecureString에 두지 않는 이유: 인스턴스 롤에 붙는 AmazonSSMManagedInstanceCore가
# ssm:GetParameter를 모든 파라미터에 허용하고, 기본 키(aws/ssm)로 암호화한 값은 계정 안 누구나 복호화할 수 있다.
# 즉 k8s 노드·모니터링·k6 인스턴스에서도 읽힌다. Secrets Manager는 롤마다 경로로 묶여 있고(k8s 노드는 app/*·infra/*),
# prod/ 경로는 이 인스턴스 롤만 읽는다. 그래서 아래에서 설정 경로에 SecureString이 섞이면 거부한다.
#
# 값을 못 받거나 필수 키가 빠지면 기존 .env.prod를 건드리지 않고 실패로 끝난다. 값은 화면·로그에 출력하지 않는다.
set -euo pipefail

REGION="ap-northeast-2"
CONFIG_PATH="/brbs/prod/env/"
SECRET_ID="prod/backend"
FCM_KEY="FCM_SERVICE_ACCOUNT_JSON"
FCM_FILE="/home/ubuntu/secrets/firebase-adminsdk.json"
FCM_CONTAINER_PATH="/etc/secrets/firebase-adminsdk.json"
CA_FILE="/home/ubuntu/certs/rds-global-bundle.pem"
CA_URL="https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"
REQUIRED_KEYS="ECR_REGISTRY DB_HOST DB_NAME DB_USER DB_PASSWORD DATABASE_URL JWT_SECRET APPLE_TOKEN_ENC_KEY
S3_BUCKET SQS_OCR_QUEUE_NAME SQS_REPORT_JOB_QUEUE_URL PII_KMS_KEY_ID PII_KMS_KEY_ARN AI_API_KEY EMBEDDING_API_KEY"

cd "$(dirname "$0")"
umask 077
work=$(mktemp -d .sync.XXXXXX)
trap 'rm -rf "$work"' EXIT

# CLI가 페이지(10개 단위)를 알아서 이어 받아 Parameters 하나로 합친다.
aws ssm get-parameters-by-path --region "$REGION" --path "$CONFIG_PATH" --recursive --output json > "$work/config.json"
aws secretsmanager get-secret-value --region "$REGION" --secret-id "$SECRET_ID" \
  --query SecretString --output text > "$work/secret.json"

# compose의 .env는 따옴표 없는 값 안의 $NAME을 다른 변수로 치환하고 " #" 뒤를 주석으로 잘라낸다. 작은따옴표로 감싸면
# 값이 그대로 쓰인다. 작은따옴표 안에는 작은따옴표·줄바꿈을 안전하게 넣을 방법이 없어 그런 값은 거부한다.
# FCM 키(JSON 파일 내용)는 .env가 아니라 파일로 쓰므로 여기서 뺀다.
python3 - "$work" "$FCM_KEY" > "$work/env" <<'PY'
import json
import sys

work, fcm_key = sys.argv[1], sys.argv[2]
values = {}
for param in json.load(open(f"{work}/config.json"))["Parameters"]:
    key = param["Name"].rsplit("/", 1)[1]
    if param["Type"] == "SecureString":
        sys.exit(f"{key}가 SecureString입니다 — 시크릿은 Secrets Manager prod/backend에 둘 것(스크립트 상단 참고)")
    values[key] = param["Value"]
for key, value in json.load(open(f"{work}/secret.json")).items():
    if key == fcm_key:
        continue
    if key in values:
        sys.exit(f"{key}가 설정과 시크릿 양쪽에 있습니다 — 한 곳에만 둘 것")
    values[key] = value
for key in sorted(values):
    value = values[key]
    if "'" in value or "\n" in value:
        sys.exit(f"{key} 값에 작은따옴표나 줄바꿈이 있어 .env에 그대로 쓸 수 없습니다")
    print(f"{key}='{value}'")
PY

for key in $REQUIRED_KEYS; do
  if ! grep -Eq "^${key}='.+'$" "$work/env"; then
    echo "${key} 값이 없습니다(${CONFIG_PATH} 또는 ${SECRET_ID})" >&2
    exit 1
  fi
done

# FCM 키 — 있으면 파일을 배치하고 .env.prod에 경로를 넣는다. 없으면 경로를 넣지 않아 stub 모드로 기동한다
# (compose의 FCM_SERVICE_ACCOUNT_PATH는 값 없이 적혀 있어 .env.prod에 없으면 컨테이너로 넘어가지 않는다).
rc=0
python3 - "$work" "$FCM_KEY" > "$work/fcm.json" <<'PY' || rc=$?
import json
import sys

work, fcm_key = sys.argv[1], sys.argv[2]
raw = json.load(open(f"{work}/secret.json")).get(fcm_key)
if not raw:
    sys.exit(3)
if json.loads(raw).get("type") != "service_account":
    sys.exit(f"{fcm_key}가 service account JSON이 아닙니다")
print(raw)
PY
case "$rc" in
  0)
    install -d -m 700 "$(dirname "$FCM_FILE")"
    install -m 600 "$work/fcm.json" "$FCM_FILE"
    echo "FCM_SERVICE_ACCOUNT_PATH='${FCM_CONTAINER_PATH}'" >> "$work/env"
    echo "FCM 키 배치: ${FCM_FILE} (실발송)"
    ;;
  3)
    echo "FCM 키 없음: ${SECRET_ID}에 ${FCM_KEY}가 없어 stub 모드(푸시 미발송)로 기동합니다"
    ;;
  *)
    exit 1
    ;;
esac

mv "$work/env" .env.prod
echo ".env.prod 갱신(권한 600): $(cut -d= -f1 .env.prod | tr '\n' ' ')"

# RDS CA 번들(공개 인증서) — 없을 때만 받는다.
if [ ! -s "$CA_FILE" ]; then
  install -d -m 755 "$(dirname "$CA_FILE")"
  curl -fsSL "$CA_URL" -o "$work/ca.pem"
  grep -q "BEGIN CERTIFICATE" "$work/ca.pem" || { echo "RDS CA 번들 형식이 아닙니다" >&2; exit 1; }
  install -m 644 "$work/ca.pem" "$CA_FILE"
  echo "RDS CA 번들 배치: ${CA_FILE}"
fi
