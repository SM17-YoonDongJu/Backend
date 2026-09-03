# Grafana 대시보드 (파일 프로비저닝 — 자동 로드)

이 폴더의 `*.json`은 Grafana 기동 시 **자동 등록**되고 30초마다 재스캔된다
(`../provisioning/dashboards/dashboards.yml`). 리포가 진실원이며, UI에서 만진 건 export → 여기 커밋으로 환류한다.

## 커밋된 대시보드
| 파일 | 대시보드 | 용도 | 데이터 조건 |
|------|----------|------|-------------|
| `1860.json` | Node Exporter Full | t3·g6 시스템(CPU·메모리·디스크·네트워크) | node_exporter UP |
| `14282.json` | Cadvisor exporter | 컨테이너별 리소스 | cAdvisor UP |
| `763.json` | Redis Dashboard for Prometheus Redis Exporter | ops/sec·히트율·evicted keys·connected clients·메모리 | redis-exporter UP. 원본의 `namespace` 변수는 이 exporter 기본 출력에 없는 라벨이라 제거하고 `instance` 변수 쿼리를 직접 참조로 바꿔 받았다(원본 그대로 쓰면 전 패널 No data) |
| `12900.json` | SpringBoot APM Dashboard | HTTP·HikariCP·로그·메모리풀 | ⚠️ **`application` 라벨 필요** |
| `4701.json` | JVM (Micrometer) | JVM 힙·GC·스레드·버퍼풀 | ⚠️ **`application` 라벨 필요** |
| `cost-optimization.json` | 비용 최적화 (커스텀, #88) | 라이트사이징 p95·GPU 가동 효율·컨테이너 소비·용량 예측 | t3 행 즉시, GPU 행은 g6 exporter 후 |
| `gpu-dcgm.json` | GPU (DCGM) — g6 (커스텀) | GPU VRAM/util·온도·전력·SM/MEM 클럭 (ollama 언로드·큐잉·스로틀링 관측) | dcgm-exporter(g6 `:9400`) UP |
| `api-latency-percentiles.json` | API 지연시간 (커스텀) | uri별 RPS·p50/p95/p99, 전체 요약, 가장 느린 API Top 10 | ⚠️ **`management.metrics.distribution.percentiles-histogram.http.server.requests=true` 필요**(히스토그램 버킷 없으면 `histogram_quantile`이 No data) + `application` 라벨 |
| `k6-load-test.json` | k6 부하테스트 (커스텀) | k6 클라이언트 지표(RPS·실패율·VU·http_req_duration/waiting/blocked/connecting/tls_handshaking percentile·dropped_iterations) | k6를 `-o experimental-prometheus-rw`로 이 Prometheus에 remote-write할 때만 데이터 유입 |
| `rds-infra.json` | RDS 인프라 (커스텀, CloudWatch) | RDS CPU·커넥션·메모리·스토리지·IOPS·레이턴시·복제 지연 | ⚠️ **모니터링 인스턴스 IAM Role에 CloudWatch 조회 권한 필요**(아래 참고) — 없으면 패널 전부 에러 |
| `websocket-chat.json` | 채팅 WebSocket (커스텀) | 핸드셰이크·구독 성공률, 활성 연결·구독 수, Redis relay 발행/전달 처리량 | 배포 즉시(커스텀 계측이라 별도 exporter·설정 불필요) + `application` 라벨 |

> ⚠️ `application` 라벨은 앱의 `management.metrics.tags.application=${spring.application.name}`
> (PR #132)이 채운다. 이 설정 없이는 12900·4701의 application 변수가 비어 패널이 No data가 된다.

## k6 → Prometheus 연동 (k6-load-test.json)
Prometheus가 Tempo용으로 이미 `--web.enable-remote-write-receiver`를 켜둔 상태라 k6가 직접 push할 수 있다.
기본 trend stat은 `p99` 하나뿐이라 `K6_PROMETHEUS_RW_TREND_STATS`를 명시해야 p50/p90/p95 패널에 데이터가 찬다.
`testid` 태그로 실행 회차를 구분한다(안 주면 여러 번 돌린 기록이 한 시계열에 섞인다).

```bash
K6_PROMETHEUS_RW_SERVER_URL=http://<prometheus-host>:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS='p(50),p(90),p(95),p(99),avg,min,max' \
k6 run -o experimental-prometheus-rw --tag testid=<실행식별자> script.js
```
로컬에서 실 모니터링 인스턴스로 쏘려면 SSM 포트포워드(`deploy/README.md`)로 `9090`을 먼저 열어야 한다.

## RDS 인프라 지표에 필요한 IAM 권한 (rds-infra.json)
CloudWatch 데이터소스는 모니터링 인스턴스의 IAM Role(`brbs-monitoring-ec2-role`)로 인증한다(정적 키 없음, 이 리포
전역 AWS 인증 관례와 동일). 현재 이 Role은 `AmazonSSMManagedInstanceCore`만 붙어 있어 CloudWatch 조회 권한이
전혀 없다 — 아래 인라인 정책을 붙여야 `rds-infra.json` 패널에 데이터가 찬다(콘솔 IAM 변경이라 리포 코드로는 못 함):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:GetMetricData",
        "cloudwatch:GetMetricStatistics",
        "cloudwatch:ListMetrics",
        "cloudwatch:DescribeAlarmsForMetric",
        "tag:GetResources",
        "ec2:DescribeTags",
        "ec2:DescribeInstances",
        "ec2:DescribeRegions"
      ],
      "Resource": "*"
    }
  ]
}
```

## 새 대시보드 추가 규칙
grafana.com JSON은 `${DS_PROMETHEUS}` 같은 **`__inputs` 변수**를 쓰는데, 파일 프로비저닝은
Import UI와 달리 이를 **해석하지 않는다** → 그대로 넣으면 "datasource not found"로 패널이 깨진다.
받은 뒤 반드시 프로비저닝 데이터소스 uid(`prometheus`)로 치환해 커밋한다:

```bash
cd deploy/monitoring/grafana/dashboards
id=<대시보드ID>
curl -fsSL "https://grafana.com/api/dashboards/${id}/revisions/latest/download" -o "${id}.json"
python - <<'EOF'
import re, sys, glob
for f in glob.glob('*.json'):
    s = open(f, encoding='utf-8').read()
    open(f, 'w', encoding='utf-8').write(re.sub(r'\$\{DS_[A-Z0-9_]+\}', 'prometheus', s))
EOF
```
