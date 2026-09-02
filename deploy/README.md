# Dev 배포 가이드

dev EC2 배포의 단일 진실원. compose는 이 디렉터리를 PR로 수정하며, **서버에서 직접 편집하지 않는다**
(배포 시 CD가 `deploy/docker-compose.dev.yml`(앱 인스턴스)·`deploy/docker-compose.monitoring.yml`(모니터링
인스턴스)을 각각 EC2로 덮어씀).

## 구성

dev는 **EC2 두 대**로 나뉜다 — 앱 인스턴스(`brbs-backend-dev`)와 모니터링 인스턴스(`brbs-monitoring`, #214).

| 구성요소 | 형태 | 인스턴스 | 비고 |
|----------|------|----------|------|
| `backend` | 컨테이너(ECR 이미지) | 앱 | CD가 `--no-deps`로 앱만 재기동 |
| `report` / `chatbot` | 컨테이너(ECR 이미지, AI 레포) | 앱 | AI 레포가 두 이미지로 배포. `repository_dispatch`(deploy-report/deploy-chatbot)로 자동 CD |
| PostgreSQL | **외부 RDS** | - | `.env.dev`의 `DB_HOST`로 연결 |
| Redis | **컨테이너** | 앱 | `restart: unless-stopped`로 상주 (이후 ElastiCache 재검토) |
| SQS (OCR 트리거) | **관리형 AWS** | - | 컨테이너 없음. 인스턴스 IAM Role로 접속(정적 키 미주입) |
| node-exporter / cAdvisor / Alloy | 컨테이너 | 앱 | 그 호스트 자체를 감시하는 에이전트라 이동 불가. 모니터링 인스턴스가 원격 스크랩/push 대상 |
| Prometheus / Grafana / Loki / Tempo | 컨테이너 | 모니터링 | `docker-compose.monitoring.yml`. 상세는 하단 "관측성 스택" 참고 |

## 배포 흐름 (자동, `develop` push 시)
`.github/workflows/deploy-dev.yml`은 인스턴스별로 독립된 job 두 개를 돈다:
1. **`deploy-to-ec2`(앱 인스턴스)**: 이미지 빌드 → ECR push → SSM으로 **리포 compose를 `~/backend/docker-compose.yml`로 동기화** → `docker compose --env-file .env.dev up -d --no-deps backend` (alloy 설정도 변경분만 동기화 후 재생성)
2. **`deploy-monitoring`(모니터링 인스턴스)**: `deploy/docker-compose.monitoring.yml`·`deploy/monitoring/**` 변경분이 있는
   push에서만 돈다(`detect-monitoring-changes`가 경로 필터로 판별 — 관련 없는 변경마다 4개 컨테이너를 재생성해 스크랩·push가
   끊기는 걸 방지). `workflow_dispatch`(수동 실행)는 변경 여부 상관없이 강제로 돈다. ECR 의존 없이 독립 실행 — SSM으로
   `docker-compose.monitoring.yml`·`deploy/monitoring/`를 `~/monitoring/`에 동기화 → prometheus·grafana·loki·tempo `--force-recreate`

> `--no-deps`라 앱 배포는 backend만 bounce한다. 인프라(redis)는 아래 "최초 기동"으로 상주시켜야 한다. OCR 트리거는 관리형 SQS(컨테이너 없음).

## 최초 기동 (앱 인스턴스, 새 EC2 / 인프라 부재 시 1회)
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

## 관측성 스택 (Prometheus + Grafana + Loki + Tempo, #88 / 인스턴스 분리 #214)

**별도 모니터링 인스턴스(`brbs-monitoring`)** 에서 `docker-compose.monitoring.yml`로 기동한다. 설정은
`deploy/monitoring/`(prometheus·grafana provisioning·loki·tempo 설정)으로 관리. 전부 `restart: unless-stopped`로 상주.

node-exporter·cAdvisor·Alloy·redis-exporter는 **그 호스트 자체를 감시/수집하는 에이전트**라 이동할 수 없어 앱 인스턴스에
남아 있고(`docker-compose.dev.yml`), 모니터링 인스턴스의 Prometheus/Loki/Tempo가 원격으로 스크랩·수신한다.

> ⚠️ **이 관측성 스택은 현재 dev 전용이다.** `docker-compose.prod.yml`에는 node-exporter·cAdvisor·Alloy·
> redis-exporter가 전혀 없고, `deploy/monitoring/prometheus.yml`의 앱 스크랩 타깃도 `10.0.11.42`(t3.brbs-backend-dev)
> 하나뿐이다. prod 부하테스트·장애 조사 시 이 Grafana에서 prod 쪽 데이터는 보이지 않는다 — prod에도 같은
> 에이전트를 배포하고 별도 스크랩 타깃을 추가해야 커버된다(별도 이슈로 분리할 것).

| 구성요소 | 인스턴스 | 포트(바인딩) | 역할 |
|----------|----------|--------------|------|
| Prometheus | 모니터링 | `127.0.0.1:9090` | 스크랩·저장(로컬 TSDB, retention 15d) |
| Grafana | 모니터링 | `127.0.0.1:3000` | 대시보드(인증 필수, 외부 미노출) |
| Loki | 모니터링 | `127.0.0.1:3100`(조회) + `3100`(앱→push, private) | 로그 저장(retention 7d) |
| Tempo | 모니터링 | `127.0.0.1:3200`(조회) + `4318`(앱→push, private) | 트레이스 저장(retention 7d) |
| node-exporter | 앱 | `9100`(모니터링→scrape, private) | 앱 인스턴스 시스템 메트릭 |
| cAdvisor | 앱 | `8082`(모니터링→scrape, private) | 앱 인스턴스 컨테이너 메트릭 |
| Alloy | 앱 | `12345`(모니터링→scrape, private) | 앱 인스턴스 컨테이너 로그 수집 → Loki push |
| redis-exporter | 앱 | `9121`(모니터링→scrape, private) | Redis 내부 지표(ops/sec·히트율·evicted keys 등) |

- **원격 스크랩/push는 전부 프라이빗 IP + 보안그룹으로 제한**(외부 미노출). 양방향이라 보안그룹도 양방향으로 연다:
  - 모니터링 → 앱: `9292`(backend actuator)·`9100`(node-exporter)·`8082`(cadvisor)·`12345`(alloy)·`9121`(redis-exporter)
  - 앱 → 모니터링: `3100`(loki push)·`4318`(tempo OTLP push)
- private IP: 앱 인스턴스(`brbs-backend-dev`) `10.0.11.42`, 모니터링 인스턴스(`brbs-monitoring`) `10.0.11.48`.
  `deploy/monitoring/prometheus.yml`·`deploy/monitoring/alloy/config.alloy`에 반영됨. 인스턴스를 재생성해 IP가
  바뀌면 두 파일을 PR로 갱신한다. `.env.dev`(앱 인스턴스용, 시크릿이라 리포 밖)의 `OTLP_TRACING_ENDPOINT`도
  `http://10.0.11.48:4318/v1/traces`로 채워야 한다.
- **외부 노출 최소화**: Prometheus·Grafana·Loki·Tempo 조회 포트는 `127.0.0.1` 바인딩 → 퍼블릭 접근 불가. 접근은 **SSM 포트포워드**로:
  ```bash
  aws ssm start-session --target <모니터링-instance-id> \
    --document-name AWS-StartPortForwardingSession \
    --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'   # → http://localhost:3000 (Grafana)
  ```
- 앱 메트릭(`10.0.11.42:9292/actuator/prometheus`)은 #131 배포로 관리 포트가 열려 있어 **스택 기동 시 바로 UP**.
- GPU(g6)에 node_exporter·cAdvisor 배포 후 `monitoring/prometheus.yml`의 GPU job 주석 해제 + 보안그룹(**모니터링 인스턴스**→g6
  9100·8080 private) 허용 — 인스턴스 분리 전엔 t3→g6이었으나 스크랩 주체가 바뀌었으므로 **GPU 모니터링 구축 담당에게 새 소스(모니터링
  인스턴스 private IP)를 공유**해야 한다.
- **알림**: Grafana 통합 알림을 provisioning으로 관리(`monitoring/grafana/provisioning/alerting/`) → **Discord 2채널
  severity 라우팅** — `critical`(타깃다운·5xx)→`#alert-critical`(`ALERT_WEBHOOK_URL_CRITICAL`),
  `warning`(CPU·메모리·디스크·Hikari)→`#alert-warning`(`ALERT_WEBHOOK_URL_WARNING`), 미분류는 critical(fail-safe).
  룰/정책 변경은 리포 수정 → CD 동기화 → `docker compose restart grafana`. GPU 타깃은 붙는 순간 타깃다운 룰이 자동 커버.

### 최초 기동 (모니터링 인스턴스, 새 EC2 1회)
```bash
mkdir -p ~/monitoring && cd ~/monitoring
# 1) 리포의 배포 compose와 .env.dev(시크릿, 앱 인스턴스와 같은 파일 재사용 가능) 배치
#    - docker-compose.yml : deploy/docker-compose.monitoring.yml 내용 (CD가 이후 자동 동기화)
#    - monitoring/         : deploy/monitoring/ 내용 (CD가 이후 자동 동기화)
#    - .env.dev            : deploy/.env.dev.example 기준으로 실제 값 채움 (커밋 금지)

# 2) .env.dev 에 GRAFANA_ADMIN_PASSWORD·ALERT_WEBHOOK_URL_* 채운 뒤 전체 기동
docker compose --env-file .env.dev up -d

# 3) 타깃 상태(UP/DOWN) 확인 — 앱 인스턴스 원격 타깃(brbs-backend·node-t3·cadvisor-t3·alloy)은
#    보안그룹(모니터링→앱 9292·9100·8082·12345 허용)까지 열려 있어야 UP으로 뜬다.
curl -s localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

> **CD 동기화**: `deploy-dev.yml`의 `deploy-monitoring` job이 `docker-compose.monitoring.yml`과 함께
> **`deploy/monitoring/` 디렉터리도** tar.gz+SSM으로 모니터링 인스턴스에 동기화한다(드리프트 차단 — 서버에서
> 수동 편집 금지). 배포 시 prometheus·grafana·loki·tempo를 전부 `--force-recreate`한다(bind 마운트 stale 방지).
> **grafana provisioning(datasource·alerting) 변경도 이 재생성으로 함께 반영**된다.

## 주의
- 자격증명: S3는 **EC2 IAM Role** 자동 사용(정적 키 불필요).
- backend healthcheck는 alpine 이미지 기준 **`wget` + `/actuator/health/readiness`** (curl 미설치).
- `.env.dev`는 시크릿이므로 리포에 커밋하지 않는다. 키 목록은 `deploy/.env.dev.example` 참고.
