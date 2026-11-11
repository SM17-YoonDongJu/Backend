#!/usr/bin/env bash
# 모니터링 인스턴스의 .env.dev를 Secrets Manager(infra/monitoring-dev)에서 받아 새로 쓴다(#348).
# deploy-dev.yml의 deploy-monitoring이 설정을 바꾸기 전 맨 처음에 ~/monitoring에서 실행한다. 값을 못 받으면
# 기존 .env.dev를 건드리지 않고 실패로 끝나서, 뒤의 설정 교체·컨테이너 재생성도 돌지 않는다.
#
# 서버에 AWS CLI를 설치하지 않고 공식 이미지로 한 번 실행한다. 인스턴스 롤 자격증명은 IMDSv2로 받는데,
# 지금 홉 제한이 2라 브리지 네트워크에서도 되지만 1로 바뀌면 컨테이너에서 못 받으므로 호스트 네트워크로 실행한다.
# 값은 화면·로그에 출력하지 않는다.
set -euo pipefail

SECRET_ID="infra/monitoring-dev"
REGION="ap-northeast-2"
IMAGE="public.ecr.aws/aws-cli/aws-cli:2.37.13"
REQUIRED_KEYS="GRAFANA_ADMIN_PASSWORD ALERT_WEBHOOK_URL_CRITICAL ALERT_WEBHOOK_URL_WARNING"

umask 077
tmp=$(mktemp .env.dev.XXXXXX)
trap 'rm -f "$tmp"' EXIT

# compose의 .env는 따옴표 없는 값 안의 $NAME을 다른 변수로 치환하고 " #" 뒤를 주석으로 잘라낸다. 작은따옴표로 감싸면
# 값이 그대로 쓰인다. 작은따옴표 안에는 작은따옴표·줄바꿈을 안전하게 넣을 방법이 없어 그런 값은 거부한다.
docker run --rm --network host "$IMAGE" secretsmanager get-secret-value \
  --region "$REGION" --secret-id "$SECRET_ID" --query SecretString --output text \
  | jq -er --arg q "'" 'to_entries[]
      | if (.value | contains($q) or contains("\n"))
        then error("\(.key) 값에 작은따옴표나 줄바꿈이 있어 .env에 그대로 쓸 수 없습니다")
        else "\(.key)=\($q)\(.value)\($q)" end' > "$tmp"

for key in $REQUIRED_KEYS; do
  if ! grep -Eq "^${key}='.+'$" "$tmp"; then
    echo "${SECRET_ID}에 ${key} 값이 없습니다" >&2
    exit 1
  fi
done

mv "$tmp" .env.dev
trap - EXIT
echo ".env.dev 갱신(권한 600): $(cut -d= -f1 .env.dev | tr '\n' ' ')"
