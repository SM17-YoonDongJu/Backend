# Dev 배포 가이드

dev EC2 배포의 단일 진실원. compose는 이 디렉터리를 PR로 수정하며, **서버에서 직접 편집하지 않는다**
(배포 시 CD가 `deploy/docker-compose.dev.yml`을 EC2로 덮어씀).

## 구성

| 구성요소 | 형태 | 비고 |
|----------|------|------|
| `backend` | 컨테이너(ECR 이미지) | CD가 `--no-deps`로 앱만 재기동 |
| `report` / `chatbot` | 컨테이너(ECR 이미지, AI 레포) | AI 레포가 두 이미지로 배포. `repository_dispatch`(deploy-report/deploy-chatbot)로 자동 CD |
| PostgreSQL | **외부 RDS** | `.env.dev`의 `DB_HOST`로 연결 |
| Redis | **컨테이너** | `restart: unless-stopped`로 상주 (이후 ElastiCache 재검토) |
| SQS (OCR 트리거) | **관리형 AWS** | 컨테이너 없음. 인스턴스 IAM Role로 접속(정적 키 미주입) |

## 배포 흐름 (자동, `develop` push 시)
`.github/workflows/deploy-dev.yml`:
1. 이미지 빌드 → ECR push
2. SSM으로 EC2에서: **리포 compose를 `~/backend/docker-compose.yml`로 동기화** → `docker compose --env-file .env.dev up -d --no-deps backend`

> `--no-deps`라 배포는 앱만 bounce한다. 인프라(redis)는 아래 "최초 기동"으로 상주시켜야 한다. OCR 트리거는 관리형 SQS(컨테이너 없음).

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
# actuator는 관리 포트 9292(호스트 게시). 비즈니스 API는 8080.
curl -s localhost:9292/actuator/health | grep -o '"status":"[A-Z]*"' | head -1   # UP
```

## 헬스 확인
```bash
docker ps --format '{{.Names}}\t{{.Status}}'
# actuator = 관리 포트 9292(8080은 비즈니스 API 전용, actuator 미제공)
curl -s localhost:9292/actuator/health          # 컴포넌트별(db/redis 등)
curl -s -o /dev/null -w '%{http_code}\n' localhost:9292/actuator/health/readiness
```

## 관측성 스택 (Prometheus + Grafana, #88)

별도 모니터링 인스턴스 없이 **t3 App 인스턴스에 콜로케이트**한다. 스택은 `docker-compose.dev.yml`에 포함되고
설정은 `deploy/monitoring/`(prometheus·grafana provisioning)로 관리한다. 전부 `restart: unless-stopped`로 상주.

| 구성요소 | 포트(바인딩) | 역할 |
|----------|--------------|------|
| Prometheus | `127.0.0.1:9090` | 스크랩·저장(로컬 TSDB, retention 15d) |
| Grafana | `127.0.0.1:3000` | 대시보드(인증 필수, 외부 미노출) |
| node-exporter | (미게시, 내부 `:9100`) | t3 시스템 메트릭 |
| cAdvisor | (미게시, 내부 `:8080`) | t3 컨테이너 메트릭 |

- **스크랩은 전부 내부망(brbs-net)** 에서 컨테이너 이름으로 수행 → exporter·앱 메트릭 포트는 **호스트에 미게시**.
- **외부 노출 최소화**: Prometheus·Grafana는 `127.0.0.1` 바인딩 → 퍼블릭 접근 불가. 접근은 **SSM 포트포워드**로:
  ```bash
  aws ssm start-session --target <t3-instance-id> \
    --document-name AWS-StartPortForwardingSession \
    --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'   # → http://localhost:3000 (Grafana)
  ```
- 앱 메트릭(`backend:9292/actuator/prometheus`)은 #131 배포로 관리 포트가 열려 있어 **스택 기동 시 바로 UP**.
- g6에 node_exporter·cAdvisor 배포 후 `monitoring/prometheus.yml`의 GPU job 주석 해제 + 보안그룹(t3→g6 9100·8080 private) 허용.
- **알림**: Grafana 통합 알림을 provisioning으로 관리(`monitoring/grafana/provisioning/alerting/`) → **Discord 2채널
  severity 라우팅** — `critical`(타깃다운·5xx)→`#alert-critical`(`ALERT_WEBHOOK_URL_CRITICAL`),
  `warning`(CPU·메모리·디스크·Hikari)→`#alert-warning`(`ALERT_WEBHOOK_URL_WARNING`), 미분류는 critical(fail-safe).
  룰/정책 변경은 리포 수정 → CD 동기화 → `docker compose restart grafana`. GPU 타깃은 붙는 순간 타깃다운 룰이 자동 커버.

### 기동
```bash
cd ~/backend
# .env.dev 에 GRAFANA_ADMIN_PASSWORD 채운 뒤 (미설정 시 grafana 기동 실패)
docker compose --env-file .env.dev up -d prometheus grafana node-exporter cadvisor
# 타깃 상태(UP/DOWN) 확인
curl -s localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

> **CD 동기화**: `deploy-dev.yml`이 `docker-compose.dev.yml`과 함께 **`deploy/monitoring/` 디렉터리도**
> tar.gz+SSM으로 EC2에 동기화한다(드리프트 차단 — 서버에서 수동 편집 금지). 배포 시 prometheus엔 SIGHUP을
> 보내 스크랩 설정을 자동 리로드하고, 대시보드 JSON은 grafana가 30초마다 재스캔한다.
> 단 **grafana provisioning(datasource·alerting) 변경은 재시작 필요**: `docker compose restart grafana`.

## 주의
- 자격증명: S3는 **EC2 IAM Role** 자동 사용(정적 키 불필요).
- backend healthcheck는 alpine 이미지 기준 **`wget` + `/actuator/health/readiness`** (curl 미설치).
- `.env.dev`는 시크릿이므로 리포에 커밋하지 않는다. 키 목록은 `deploy/.env.dev.example` 참고.
