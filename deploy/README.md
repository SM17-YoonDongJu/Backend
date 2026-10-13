# Dev 배포 가이드

dev는 두 갈래로 배포된다.

- **앱**(backend·report·chatbot·redis)은 soma-k8s 클러스터(kubeadm)에서 **Argo CD**가 배포한다. 매니페스트와
  앱 설정(ConfigMap 값·ExternalSecret)은 이 레포가 아니라 `SM17-YoonDongJu/K8s-manifests`(private)에 있다.
- **관측성 스택**(prometheus·grafana·loki·tempo)은 별도 모니터링 EC2(`brbs-monitoring`, #214)에서 docker compose로
  돈다. compose와 설정은 이 디렉터리가 단일 진실원이고, **서버에서 직접 편집하지 않는다**(CD가 덮어씀).

## 디렉터리

| 경로 | 내용 | 배포 주체 |
|------|------|-----------|
| `monitoring/` | 중앙 관측성 스택 — compose와 컨테이너에 마운트되는 설정(`config/`) | `deploy-dev.yml`의 `deploy-monitoring` job (SSM) |
| `prod/` | prod 앱 스택(EC2) — compose와 env 키 목록 | `deploy-prod.yml` (SSM) |

**클러스터에 들어가는 것은 이 레포에 두지 않는다.** 차트·매니페스트·values·Argo CD Application 정의는 전부
K8s-manifests에 있고 Argo CD는 그 레포만 본다. 여기에 `k8s/` 같은 디렉터리를 만들면 운영 방식이 레포 단위로
갈리고 drift를 비교할 기준이 사라진다 — #338에서 한 번 그랬고 경위는 #339에 적혀 있다.

로컬 개발용 자산은 배포물이 아니므로 `docker/`에 둔다. LocalStack 큐 초기화 스크립트가 그 경우다.

## 구성

| 구성요소 | 형태 | 위치 | 비고 |
|----------|------|------|------|
| `backend` | Deployment(ECR 이미지) | soma-k8s | 차트 `charts/backend`, dev 값 `envs/dev/backend.values.yaml`(K8s-manifests) |
| `report` / `chatbot` | Deployment(ECR 이미지, AI 레포) | soma-k8s | 이미지 태그는 `envs/dev/report.values.yaml`·`chatbot.values.yaml` |
| Redis | StatefulSet(bitnami 차트) | soma-k8s | PVC가 노드 로컬(local-path)이라 다른 노드로 옮기면 데이터가 비워진다 |
| PostgreSQL | **외부 RDS** | - | values `config`의 `DB_HOST`로 연결 |
| SQS (OCR 트리거) | **관리형 AWS** | - | 워커 노드 IAM Role로 접속(정적 키 미주입) |
| 외부 진입 | ALB → Traefik(NodePort 30080) → Ingress | - | `api-dev.brbosang.com`. 관리 포트 9292는 `/actuator/health`·`/actuator/info`만 외부에 연다 |
| Prometheus / Grafana / Loki / Tempo | 컨테이너 | 모니터링 EC2 | `monitoring/docker-compose.yml`. 상세는 하단 "관측성 스택" 참고 |

## 배포 흐름 (자동, `develop` push 시)

`.github/workflows/deploy-dev.yml`이 아래 순서로 돈다. CI는 클러스터 자격증명을 갖지 않고, 클러스터에 쓰는 건
Argo CD뿐이다.

1. **`build-and-push`**: 이미지 빌드(커밋 해시를 `GIT_COMMIT` build-arg로 굽는다) → ECR에 전체 SHA·짧은 SHA·`dev` 태그로 push
2. **`update-manifests`**: `brbs-manifests-ci` GitHub App 토큰으로 K8s-manifests `envs/dev/backend.values.yaml`의
   `image.tag`를 짧은 SHA로 바꿔 main에 커밋
3. **Argo CD**: 레포 변경을 감지(최대 3분)해 Sync → 롤링 업데이트(새 파드가 readiness를 통과한 뒤 구 파드 종료)
4. **`verify-deploy`**: `https://api-dev.brbosang.com/actuator/info`의 `app.commit`이 이번 커밋과 같아지고
   `/v3/api-docs`가 200인지 최대 15분 기다려 확인
5. **`deploy-monitoring`(모니터링 인스턴스)**: `deploy/monitoring/**` 변경분이 있는
   push에서만 돈다(`detect-monitoring-changes`가 경로 필터로 판별 — 관련 없는 변경마다 4개 컨테이너를 재생성해 스크랩·push가
   끊기는 걸 방지). `workflow_dispatch`(수동 실행)는 변경 여부 상관없이 강제로 돈다. ECR 의존 없이 독립 실행 — SSM으로
   `deploy/monitoring/`의 compose와 `config/`를 `~/monitoring/`에 동기화 → prometheus·grafana·loki·tempo `--force-recreate`
6. **`notify`**: 결과를 Discord로 보낸다. 3~4까지 통과해야 "완료"로 나간다

> `dev` 태그는 "가장 최근 dev 빌드" 표시용이고 매니페스트에는 쓰지 않는다. 이름이 그대로인 태그는 매니페스트가
> 바뀌지 않아 Argo CD가 반영하지 않고, 노드에 캐시된 옛 이미지가 그대로 쓰인다(`IfNotPresent`).

## 설정 바꾸기

- **일반 값**: K8s-manifests `envs/dev/backend.values.yaml`의 `config`를 PR로 고친다. 머지되면 Argo CD가 ConfigMap을
  갱신하고, 파드 템플릿의 `checksum/config` 어노테이션이 바뀌어 backend가 자동으로 재시작된다.
- **시크릿**: Secrets Manager `app/backend-dev`의 값을 고친다. ESO가 1시간 주기로 k8s Secret(`backend-dev-secrets`)에
  반영하는데, Secret이 바뀌어도 파드는 자동으로 재시작되지 않는다(Reloader 미도입). 바로 반영하려면 아래처럼 즉시
  동기화한 뒤 Argo CD UI에서 backend Deployment의 Restart 액션을 실행한다. 새 키를 추가할 때는 K8s-manifests
  `envs/dev/manifests/externalsecret-backend-dev-secrets.yaml`의 `data`에도 키를 넣어야 한다.

```bash
# control-plane에서 — ESO 즉시 동기화
sudo kubectl --kubeconfig /etc/kubernetes/admin.conf annotate externalsecret backend-dev-secrets \
  force-sync=$(date +%s) --overwrite
```

## 롤백

K8s-manifests에서 해당 봇 커밋(`chore(dev): backend 이미지를 …로 갱신`)을 revert하는 PR을 머지한다. Argo CD UI의
History에도 Rollback이 있지만 auto-sync가 켜진 앱에서는 막혀 있고, 쓰더라도 Git과 클러스터가 어긋나므로 Git으로
되돌리는 게 원칙이다. 되돌아갔는지는 `/actuator/info`의 `app.commit`으로 확인한다.

## 상태 확인
```bash
# 외부에서 — 지금 떠 있는 이미지의 커밋과 헬스
curl -s https://api-dev.brbosang.com/actuator/info | jq -r .app.commit
curl -s https://api-dev.brbosang.com/actuator/health

# 클러스터 안 — control-plane(k8s-server)에 SSM으로 접속해서
sudo kubectl --kubeconfig /etc/kubernetes/admin.conf get pods -l app=backend
sudo kubectl --kubeconfig /etc/kubernetes/admin.conf logs deploy/backend --tail=100
```

Argo CD UI는 SSM 포트포워딩(`AWS-StartPortForwardingSessionToRemoteHost`, 대상 control-plane, host는 `argocd-server`
서비스 ClusterIP, 포트 443)으로 연다.

## 관측성 스택 (Prometheus + Grafana + Loki + Tempo, #88 / 인스턴스 분리 #214)

**별도 모니터링 인스턴스(`brbs-monitoring`)** 에서 `monitoring/docker-compose.yml`로 기동한다. 설정은
`deploy/monitoring/config/`(prometheus·grafana provisioning·loki·tempo 설정)으로 관리. 전부 `restart: unless-stopped`로 상주.

**dev 앱 스택은 soma-k8s 클러스터로 이주했다(#328).** 구 앱 인스턴스의 compose 에이전트(node-exporter·cAdvisor·
Alloy·redis-exporter)는 docker 제거와 함께 소멸했고, 수집은 클러스터 안 kube-prometheus-stack(node-exporter
DaemonSet·kubelet cAdvisor·kube-state-metrics)이 맡아 이 인스턴스의 Prometheus로 remote_write한다
(`cluster="soma-k8s"` 라벨로 유입). 로그는 alloy DaemonSet 전환 전까지 Loki 신규 유입이 없다(후속).

> ⚠️ **이 관측성 스택은 현재 dev 전용이다.** prod(`prod/docker-compose.yml`)에는 수집 에이전트가 전혀 없고
> remote_write를 보내는 클러스터도 dev(soma-k8s)뿐이다. prod 부하테스트·장애 조사 시 이 Grafana에서 prod 쪽
> 데이터는 보이지 않는다 — prod 수집을 따로 배선해야 커버된다(별도 이슈로 분리할 것).

| 구성요소 | 인스턴스 | 포트(바인딩) | 역할 |
|----------|----------|--------------|------|
| Prometheus | 모니터링 | `9090`(조회 + soma-k8s→remote_write push, private) | 스크랩·저장(로컬 TSDB, retention 3d, size ≤8GB) + soma-k8s 메트릭 수신(#328) |
| Grafana | 모니터링 | `127.0.0.1:3000` | 대시보드(인증 필수, 외부 미노출) |
| Loki | 모니터링 | `127.0.0.1:3100`(조회) + `3100`(앱→push, private) | 로그 저장(retention 3d) |
| Tempo | 모니터링 | `127.0.0.1:3200`(조회) + `4318`(앱→push, private) | 트레이스 저장(retention 3d) |
| node-exporter | 클러스터 | (클러스터 내부 scrape) | 노드(CP·워커) 시스템 메트릭 — KPS DaemonSet, remote_write로 유입 |
| kubelet cAdvisor | 클러스터 | (클러스터 내부 scrape) | 파드·컨테이너 메트릭 — kubelet 내장, remote_write로 유입 |
| kube-state-metrics | 클러스터 | (클러스터 내부 scrape) | k8s 상태(파드 phase·replica·requests/limits) — remote_write로 유입 |
| Alloy | (후속, #328) | - | 로그 수집 DaemonSet 전환 예정 — 전까지 Loki 신규 유입 없음 |
| redis-exporter | (후속) | - | redis가 클러스터로 이주, exporter 미배선 — 763 대시보드 No data |

- **원격 push는 전부 프라이빗 IP + 보안그룹으로 제한**(외부 미노출):
  - 워커 노드(구 앱 EC2) → 모니터링: `4318`(tempo OTLP push — 파드 발신이 노드 IP로 SNAT되어 기존 앱 SG
    참조 규칙에 걸린다)·`3100`(loki push, alloy DaemonSet 복구 후. CP에서 뜨는 파드도 push하려면 3100 소스에
    `soma-sg-k3s` 추가 필요)
  - soma-k8s 노드 → 모니터링: `9090`(prometheus remote_write push, #328)
  - (구) 모니터링 → 앱 `9292`·`9100`·`8082`·`12345`·`9121` 인바운드 규칙은 정적 스크랩 제거(#328)로 더 쓰지 않는다
- private IP: 모니터링 인스턴스(`brbs-monitoring`) `10.0.11.48`, soma-k8s 노드 `10.0.41.203`(control-plane)·
  `10.0.11.42`(워커, 구 앱 인스턴스 `brbs-backend-dev`)·`10.0.11.131`(GPU 워커, `brbs-etl`).
  인스턴스를 재생성해 IP가 바뀌면 PR로 갱신한다. backend의 `OTLP_TRACING_ENDPOINT`(K8s-manifests values `config`)는
  `http://10.0.11.48:4318/v1/traces`를 가리켜야 한다.
- **외부 노출 최소화**: Grafana(`3000`)·Tempo 조회(`3200`)는 `127.0.0.1` 바인딩 → 퍼블릭 접근 불가.
  Loki(`3100`)·Prometheus(`9090`)는 원격 push 수신 때문에 호스트 게시지만 보안그룹이 private 소스만 허용한다.
  로컬 접근은 **SSM 포트포워드**로:
  ```bash
  aws ssm start-session --target <모니터링-instance-id> \
    --document-name AWS-StartPortForwardingSession \
    --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'   # → http://localhost:3000 (Grafana)
  ```
- backend 앱 메트릭(`/actuator/prometheus`)을 구 앱 인스턴스에서 정적으로 긁던 타깃(`10.0.11.42:9292`)은 #328로
  제거됐다. 클러스터 안 backend 메트릭 수집 경로는 #328을 따른다.
- **GPU 인스턴스는 둘이고 서로 다른 작업이다.** `brbs-etl`(`10.0.11.131`, g4dn.xlarge)에서 Ollama와 약관 인덱싱이 돌고,
  `brbs-ai`(`10.0.11.93`, g6.xlarge)는 현재 stopped다. 원래 "GPU(g6)"로 뭉뚱그려 부르던 게 후자다.
  - brbs-etl 호스트 관측(node_exporter·cAdvisor·Alloy)은 #257 — 배포 후 `monitoring/prometheus.yml`의 `node-etl`·`cadvisor-etl`
    주석을 해제한다. 이 호스트에서 도는 청커 앱 지표(`insurance-chunker` 잡)는 이미 붙어 있고 그것과는 별개다.
  - brbs-ai는 인스턴스를 다시 띄운 뒤 `node-ai`·`cadvisor-ai` 주석을 해제한다.
  - **두 호스트는 보안그룹(`soma-sg-ai`)을 공유한다(분리는 #294).** 모니터링 SG 소스로 `9100`(node exporter)·
    `8080`(cAdvisor)·`9101`(청커 /metrics)이 이미 열려 있어 추가 작업이 없다(Alloy 자체 스크랩용 `12345`만 #257 때 추가).
    다만 **cAdvisor 포트가 t3(8082)와 다르니** exporter를 8080으로 띄우거나 보안그룹을 먼저 고쳐야 한다. 인스턴스 분리 전엔 t3→GPU였으므로 스크랩
    소스가 모니터링 인스턴스로 바뀐 것도 담당자에게 공유해야 한다.
  - ⚠️ **`soma-sg-ai`를 소스로 참조하는 규칙이 다른 SG에 4개 있다** — `soma-sg-rds-dev` 5432(청커 DB 접근),
    `soma-sg-monitoring` 3100(Loki 로그 push), `soma-sg-msk` 9092와 `soma-sg-backend` 9094(둘 다 Kafka 잔재).
    SG를 나누거나 교체할 때 이걸 같이 옮기지 않으면 조용히 끊긴다. 특히 **5432를 놓치면 인덱싱이 DB 연결
    실패로 죽는데 주기가 7일이라 다음 사이클까지 아무도 모른다.**
- **알림**: Grafana 통합 알림을 provisioning으로 관리(`monitoring/grafana/provisioning/alerting/`) → **Discord 2채널
  severity 라우팅** — `critical`(타깃다운·5xx)→`#alert-critical`(`ALERT_WEBHOOK_URL_CRITICAL`),
  `warning`(CPU·메모리·디스크·Hikari)→`#alert-warning`(`ALERT_WEBHOOK_URL_WARNING`), 미분류는 critical(fail-safe).
  룰/정책 변경은 리포 수정 → CD 동기화 → `docker compose restart grafana`. GPU 타깃은 붙는 순간 타깃다운 룰이 자동 커버.

### 최초 기동 (모니터링 인스턴스, 새 EC2 1회)
```bash
mkdir -p ~/monitoring && cd ~/monitoring
# 1) 리포의 배포 compose와 .env.dev(시크릿) 배치
#    - docker-compose.yml : deploy/monitoring/docker-compose.yml 내용 (CD가 이후 자동 동기화)
#    - config/             : deploy/monitoring/config/ 내용 (CD가 이후 자동 동기화)
#    - .env.dev            : deploy/monitoring/.env.example 기준으로 실제 값 채움 (커밋 금지)

# 2) .env.dev 에 GRAFANA_ADMIN_PASSWORD·ALERT_WEBHOOK_URL_* 채운 뒤 전체 기동
docker compose --env-file .env.dev up -d

# 3) 타깃 상태(UP/DOWN) 확인 — 로컬 타깃(prometheus·loki·tempo)과 GPU 호스트 타깃.
#    soma-k8s 메트릭은 스크랩이 아니라 remote_write로 들어오므로 cluster="soma-k8s" 라벨 쿼리로 확인한다.
curl -s localhost:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

> **CD 동기화**: `deploy-dev.yml`의 `deploy-monitoring` job이 `deploy/monitoring/`의 compose와 함께
> **`config/` 디렉터리도** 모니터링 인스턴스에 동기화한다. 인스턴스가 해당 커밋의 레포 tarball을 GitHub에서
> 직접 받아 푸는 방식이다(드리프트 차단 — 서버에서 수동 편집 금지). 배포 시 prometheus·grafana·loki·tempo를 전부
> `--force-recreate`한다(bind 마운트 stale 방지).
> **grafana provisioning(datasource·alerting) 변경도 이 재생성으로 함께 반영**된다.

## k6 부하테스트 — DB 버퍼 풀 축소

디스크 I/O 재현이 목적인 부하테스트에서 "진짜 기준은 행 수가 아니라 데이터 크기가 버퍼 풀을 넘는가"다(팀
Notion "부하테스트 / DB 데이터 채워넣기" 문서). dev RDS(`brbs-rds-dev`, `db.t4g.small` 2GiB)는
`default.postgres16` 파라미터 그룹을 쓰는데, 이 그룹의 `shared_buffers` 기본값은 인스턴스 메모리 공식
(`DBInstanceClassMemory/32768`)에 따라 약 **512MB**다 — 이 크기를 실데이터로 넘기려면 수백만 행이 필요해
로드테스트 준비 자체가 비현실적이다.

**해법은 버퍼 풀을 낮추는 것이다.** 커스텀 파라미터 그룹으로 `shared_buffers=32MB` /
`work_mem=1MB` / `effective_cache_size=64MB`로 낮추면 약 20만 행만으로도 디스크 I/O가 재현된다(위 Notion
문서 예시 그대로). `K6ScenarioSeedRunner`의 기본값(`APP_DEV_SEED_K6_USER_COUNT=600`, 약 202,200행)이 이
20만 행 문턱을 넘도록 역산한 수치다 — 자세한 근거는 그 클래스 Javadoc 참고.

`default.postgres16`은 AWS 기본 그룹이라 값을 직접 못 바꾼다. 커스텀 그룹을 만들어 인스턴스에 붙여야 하고,
`shared_buffers`는 **static 파라미터라 reboot 없이는 적용되지 않는다.**

> ⚠️ **`brbs-rds-dev`는 backend·report-worker·chatbot이 공유하는 dev DB다.** reboot은 그 순간 모든 연결을
> 끊는다(수십 초~1~2분). 팀에 미리 공지하고, 아무도 dev를 쓰지 않는 시간대에 진행할 것. 버퍼를 낮춘 채로
> 오래 두면 부하테스트와 무관한 평소 dev 작업(다른 사람의 API 호출·쿼리)도 느려지므로, **테스트 창이 끝나면
> 반드시 원복(reboot 1회 더)한다.**

```bash
# 1) 커스텀 파라미터 그룹 생성 (최초 1회만 — 이후엔 재사용)
aws rds create-db-parameter-group \
  --db-parameter-group-name brbs-postgres16-loadtest \
  --db-parameter-group-family postgres16 \
  --description "k6 부하테스트용 - shared_buffers 32MB 축소"

# 2) 파라미터 설정 (단위 주의: shared_buffers·effective_cache_size는 8kB 페이지, work_mem은 kB)
aws rds modify-db-parameter-group \
  --db-parameter-group-name brbs-postgres16-loadtest \
  --parameters \
    "ParameterName=shared_buffers,ParameterValue=4096,ApplyMethod=pending-reboot" \
    "ParameterName=work_mem,ParameterValue=1024,ApplyMethod=pending-reboot" \
    "ParameterName=effective_cache_size,ParameterValue=8192,ApplyMethod=pending-reboot"

# 3) 인스턴스에 연결
aws rds modify-db-instance \
  --db-instance-identifier brbs-rds-dev \
  --db-parameter-group-name brbs-postgres16-loadtest \
  --apply-immediately

# 4) reboot (static 파라미터 적용에 필수)
aws rds reboot-db-instance --db-instance-identifier brbs-rds-dev

# 5) 확인 (psql 접속 후)
# SHOW shared_buffers;          -- 32MB
# SHOW work_mem;                -- 1MB
# SHOW effective_cache_size;    -- 64MB
```

테스트 창이 끝나면 원복한다:

```bash
aws rds modify-db-instance \
  --db-instance-identifier brbs-rds-dev \
  --db-parameter-group-name default.postgres16 \
  --apply-immediately
aws rds reboot-db-instance --db-instance-identifier brbs-rds-dev
```

### 시드 러너·dev 로그인 백도어 켜기

평소엔 전부 꺼 두고 로드테스트 창에만 켠다. 키는 이미 K8s-manifests `envs/dev/backend.values.yaml`의 `config`에
기본값(false)으로 들어 있어서 값만 바꾸는 PR을 머지하면 된다. ConfigMap이 바뀌면 backend가 자동으로 재시작되고
(위 "설정 바꾸기"), 시드 러너는 부팅 때 1회 돈다. 이미 만든 데이터는 (유저, 풀) 단위로 건너뛰므로 다시 재시작돼도
증분은 0이다. 창이 끝나면 같은 방식으로 false로 되돌린다.

- **키 이름에 `APP_` 접두어가 꼭 필요하다.** 시드 게이트는 `application*.yml`에 선언하지 않고 코드의 `@Value`
  기본값으로만 있어서, Spring이 프로퍼티명(`app.dev-seed.k6-scenarios-enabled`)에서 env 키
  (`APP_DEV_SEED_K6_SCENARIOS_ENABLED`)를 직접 유도한다. 접두어가 빠지면 바인딩이 안 되고 러너가 조용히 아무것도 안 한다.
- **순서**: 사정사 계정(`APP_DEV_SEED_K6_ADJUSTERS_ENABLED`)을 먼저 1회 켜고, 그다음 시나리오 데이터
  (`APP_DEV_SEED_K6_SCENARIOS_ENABLED`)를 켠다.
- 풀 크기 키(`APP_DEV_SEED_K6_USER_COUNT`와 `..._PER_USER` 4종)의 기본값과 근거는 `K6ScenarioSeedRunner` Javadoc에
  있다. k6 VU와 계정은 1:1 매핑이 전제라, VU가 `USER_COUNT`를 넘을 때만 이 값도 같이 올린다.
- **시딩은 수 분~수십 분 걸린다.** 러너는 `ApplicationReadyEvent` 전에 돌기 때문에 그동안 새 파드는 readiness가
  UP이 아니라 트래픽을 받지 않고, 구 파드가 계속 응답한다. liveness는 UP이라 재시작되지는 않지만 10분을 넘기면
  Deployment가 `ProgressDeadlineExceeded`로 표시되고 Argo CD에서 Degraded로 보인다(시딩이 끝나면 돌아온다).
  그래도 `APP_DEV_SEED_K6_USER_COUNT=3`으로 먼저 리허설해 1유저당 소요를 재 둘 것 — 시딩 중 CPU가 limit(1코어)에
  붙으면 liveness 응답(타임아웃 기본 1초)이 늦어져 재시작될 수 있다.
- **dev 로그인 백도어**: `DEV_LOGIN_ENABLED`는 같은 `config`에 있고, `X-Dev-Login-Key`와 비교할 `DEV_LOGIN_SECRET`은
  Secrets Manager `app/backend-dev`에 있다. 시크릿이 비어 있으면 헤더 검증이 꺼져 닉네임만으로 누구든 로그인되므로,
  enabled=true로 여는 동안엔 반드시 채워 둔다.

## 주의
- 자격증명: S3·SQS·Secrets Manager 모두 워커 노드 IAM Role을 쓴다(정적 키 불필요).
- backend 프로브는 readiness `/actuator/health/readiness`, liveness `/actuator/health/liveness`(관리 포트 9292, 초기
  지연 90초)다. 정의는 K8s-manifests `charts/backend`에 있다.
- `.env.dev`는 이제 모니터링 인스턴스에서만 쓴다. 시크릿이라 커밋하지 않고, 키 목록은 `deploy/monitoring/.env.example` 참고.
- prod는 아직 `deploy-prod.yml`의 SSM + `prod/docker-compose.yml` 방식이다.
