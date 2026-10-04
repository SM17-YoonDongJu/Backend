# Grafana 대시보드 (파일 프로비저닝 — 자동 로드)

이 폴더의 `*.json`은 Grafana 기동 시 **자동 등록**되고 30초마다 재스캔된다
(`../provisioning/dashboards/dashboards.yml`). 리포가 진실원이며, UI에서 만진 건 export → 여기 커밋으로 환류한다.

## 커밋된 대시보드
| 파일 | 대시보드 | 용도 | 데이터 조건 |
|------|----------|------|-------------|
| `1860.json` | Node Exporter Full | node_exporter가 붙은 호스트의 시스템(CPU·메모리·디스크·네트워크) | soma-k8s 노드 2대(CP·워커)가 KPS node-exporter DaemonSet → remote_write로 유입(#328). brbs-etl·brbs-ai는 stopped — 재기동 + prometheus.yml 잡 주석 해제 후 |
| `14282.json` | Cadvisor exporter | 컨테이너별 리소스 | ⚠️ 구 docker 호스트 전용(cadvisor exporter 라벨 체계) — t3 에이전트 소멸·g6 stopped라 현재 No data. 클러스터 컨테이너는 Kubernetes 대시보드(15757~15760)로 본다 |
| `763.json` | Redis Dashboard for Prometheus Redis Exporter | ops/sec·히트율·evicted keys·connected clients·메모리 | ⚠️ redis가 클러스터로 이주, exporter 미배선이라 No data(후속). 원본의 `namespace` 변수는 이 exporter 기본 출력에 없는 라벨이라 제거하고 `instance` 변수 쿼리를 직접 참조로 바꿔 받았다(원본 그대로 쓰면 전 패널 No data) |
| `12900.json` | SpringBoot APM Dashboard | HTTP·HikariCP·로그·메모리풀 | ⚠️ **`application` 라벨 필요** |
| `4701.json` | JVM (Micrometer) | JVM 힙·GC·스레드·버퍼풀 | ⚠️ **`application` 라벨 필요** |
| `cost-optimization.json` | 비용 최적화 (커스텀, #88) | 라이트사이징 p95·GPU 가동 효율·컨테이너 소비·용량 예측 | ⚠️ 구 t3 cadvisor·node 라벨 기준이라 이주(#328) 후 재검토 필요 — 컨테이너 행은 소멸, GPU 행은 **brbs-etl(`g4dn-etl`)** 기준(#257·#258, stopped) |
| `gpu-dcgm.json` | GPU (DCGM) — g6 (커스텀) | GPU VRAM/util·온도·전력·SM/MEM 클럭 (ollama 언로드·큐잉·스로틀링 관측) | ⚠️ g6 stopped + `dcgm-gpu` 잡 주석(#328)이라 현재 No data — 재기동 시 prometheus.yml 잡 주석 해제와 함께 복원 |
| `api-latency-percentiles.json` | API 지연시간 (커스텀) | uri별 RPS·p50/p95/p99, 전체 요약, 가장 느린 API Top 10 | ⚠️ **`management.metrics.distribution.percentiles-histogram.http.server.requests=true` 필요**(히스토그램 버킷 없으면 `histogram_quantile`이 No data) + `application` 라벨 |
| `rds-infra.json` | RDS 인프라 (커스텀, CloudWatch) | RDS CPU·커넥션·메모리·스토리지·IOPS·레이턴시·복제 지연 | ⚠️ **모니터링 인스턴스 IAM Role에 CloudWatch 조회 권한 필요**(아래 참고) — 없으면 패널 전부 에러 |
| `websocket-chat.json` | 채팅 WebSocket (커스텀) | 핸드셰이크·구독 성공률, 활성 연결·구독 수, Redis relay 발행/전달 처리량 | 배포 즉시(커스텀 계측이라 별도 exporter·설정 불필요) + `application` 라벨 |
| `insurance-chunker.json` | 약관 인덱싱 (커스텀, #259) | 마지막 성공 이후 경과·사이클 성패·상태별 문서·격리/경계 weak 문서·단계별 시간 비중 | ⚠️ brbs-etl stopped + `insurance-chunker` 잡 주석(#328)이라 현재 No data — 재기동 시 잡 주석 해제·IndexNeverSucceeded unpause와 함께 복원. 복원 후에도 **7일 주기 배치라 카운터가 안 움직이는 게 정상** — 주 신호는 '마지막 실행 상태' 게이지다. 첫 사이클이 성공하기 전에는 신선도 패널이 `성공 기록 없음`으로 뜬다(지표 자체가 없음) |
| `15757.json` | Kubernetes / Views / Global | soma-k8s 클러스터 전체 — 실사용(usage)과 예약(requests/limits vs allocatable) 요약 | ⚠️ **soma-k8s remote_write(#328) 필요** — 아래 참고 |
| `15758.json` | Kubernetes / Views / Namespaces | 네임스페이스별 리소스 | 〃 |
| `15759.json` | Kubernetes / Views / Nodes | 클러스터 노드(=EC2)별 시스템 | 〃 |
| `15760.json` | Kubernetes / Views / Pods | 파드·컨테이너별 리소스/재시작 | 〃 |
| `15761.json` | Kubernetes / System / API Server | 컨트롤플레인(apiserver) 요청량·지연·에러 | 〃. 원본(dotdc) 단위 버그를 고쳐서 받았다 — 지연 패널 2개 `ms`→`s`(쿼리 결과가 초), CPU 패널 `percent`→`percentunit`(코어 분율, 1.0=코어 1개 100%) |
| `cilium-agent.json` | Cilium Metrics | CNI 데이터플레인 — 에이전트 상태·BPF 맵·정책 적용·엔드포인트·API 지연 (Calico→Cilium 전환, 2026-10-04) | ⚠️ soma-k8s remote_write + **SG 9962-9965**(KPS가 CP에서 워커의 hostNetwork 메트릭 포트를 스크랩) — 둘 다 적용됨. 공식 대시보드를 Cilium 레포 v1.20.2 태그 동봉본으로 받았다(grafana.com 16611은 v1.12 시절이라 메트릭 어긋남) |
| `hubble-metrics.json` | Hubble Metrics and Monitoring | 네트워크 플로우 — 처리율·드롭·TCP 플래그·DNS 질의/실패·포트 분포 | 〃. hubble.metrics 활성 목록(dns·drop·tcp·flow·port-distribution·icmp·httpV2)은 CP `/root/cilium-values.yaml`이 진실 — IP 라벨은 카디널리티 때문에 뺐으니(네임스페이스/워크로드 수준만) IP 단위 패널은 비는 게 정상 |
| `hubble-network-overview.json` | Hubble / Network Overview (Namespace) | 네임스페이스 간 트래픽 조감 — 소스/목적지별 플로우·드롭 비중 | 〃. HTTP(L7) 패널은 L7 가시성 애노테이션을 단 파드에만 데이터가 찬다(현재 미적용 — httpV2 시리즈 0이 정상) |

> ⚠️ Kubernetes 5종(dotdc 세트)은 soma-k8s의 kube-prometheus-stack이 중앙 Prometheus로 **remote_write**해야
> 데이터가 찬다(#328 — node-exporter DaemonSet·kube-state-metrics·kubelet/cAdvisor 메트릭). 전부 `cluster` 변수
> (`label_values(kube_node_info, cluster)`)로 필터하므로 클러스터 쪽 설치 전에는 변수부터 비어 No data가 정상이고,
> EC2 쪽 시리즈(t3 cadvisor의 `container_*` 등)는 `cluster`·k8s 라벨이 없어 여기 섞이지 않는다.

> ⚠️ `application` 라벨은 앱의 `management.metrics.tags.application=${spring.application.name}`
> (PR #132)이 채운다. 이 설정 없이는 12900·4701의 application 변수가 비어 패널이 No data가 된다.

> **k6 부하테스트 결과는 이 Prometheus/Grafana가 아니라 Datadog으로 보낸다** — 운영/인프라 관측성(Prometheus+Grafana)과
> 부하테스트 결과(k6→Datadog)를 분리하기로 했다. 예전에 있던 `k6-load-test.json`(Prometheus remote-write 연동)은
> 그래서 제거했다.

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
