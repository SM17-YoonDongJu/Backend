# Dev 배포 가이드

dev EC2 배포의 단일 진실원. compose는 이 디렉터리를 PR로 수정하며, **서버에서 직접 편집하지 않는다**
(배포 시 CD가 `deploy/docker-compose.dev.yml`을 EC2로 덮어씀).

## 구성

| 구성요소 | 형태 | 비고 |
|----------|------|------|
| `backend` | 컨테이너(ECR 이미지) | CD가 `--no-deps`로 앱만 재기동 |
| `chatbot` / `reportworker` | 컨테이너(ECR 이미지, AI 레포) | AI 레포가 두 이미지로 배포. 최초 full up으로 상주 |
| PostgreSQL | **외부 RDS** | `.env.dev`의 `DB_HOST`로 연결 |
| Redis / Kafka | **컨테이너** | `restart: unless-stopped`로 상주. **8월 전까지 컨테이너 유지**, 이후 ElastiCache/MSK 재검토 |

## 배포 흐름 (자동, `develop` push 시)
`.github/workflows/deploy-dev.yml`:
1. 이미지 빌드 → ECR push
2. SSM으로 EC2에서: **리포 compose를 `~/backend/docker-compose.yml`로 동기화** → `docker compose --env-file .env.dev up -d --no-deps backend`

> `--no-deps`라 배포는 앱만 bounce한다. 인프라(redis/kafka)는 아래 "최초 기동"으로 상주시켜야 한다.

## 최초 기동 (새 EC2 / 인프라 부재 시 1회)
```bash
cd ~/backend
# 1) 리포의 배포 compose와 .env.dev(시크릿) 배치
#    - docker-compose.yml : deploy/docker-compose.dev.yml 내용 (CD가 이후 자동 동기화)
#    - .env.dev           : deploy/.env.dev.example 기준으로 실제 값 채움 (커밋 금지)

# 2) 전체 인프라 + 앱 기동
docker compose --env-file .env.dev up -d

# 3) 상태 확인
docker ps --format '{{.Names}}\t{{.Status}}'          # 전부 (healthy)
curl -s localhost:8080/actuator/health | grep -o '"status":"[A-Z]*"' | head -1   # UP
```

## 헬스 확인
```bash
docker ps --format '{{.Names}}\t{{.Status}}'
curl -s localhost:8080/actuator/health          # 컴포넌트별(db/redis 등)
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/health/readiness
```

## 주의
- 자격증명: S3는 **EC2 IAM Role** 자동 사용(정적 키 불필요).
- backend healthcheck는 alpine 이미지 기준 **`wget` + `/actuator/health/readiness`** (curl 미설치).
- `.env.dev`는 시크릿이므로 리포에 커밋하지 않는다. 키 목록은 `deploy/.env.dev.example` 참고.
