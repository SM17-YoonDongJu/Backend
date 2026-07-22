# Grafana 대시보드 (provisioning 자동 로드)

이 폴더의 `*.json` 은 Grafana 기동 시 **자동 등록**된다(`../provisioning/dashboards/dashboards.yml`).
[grafana.com/dashboards](https://grafana.com/grafana/dashboards/) 에서 아래 ID의 JSON을 내려받아 이 폴더에 저장한다.
(내려받을 때 데이터소스는 프로비저닝된 `Prometheus`(uid) 로 맞춘다.)

| 용도 | 대시보드 | ID | 커버 이슈 |
|------|----------|----|-----------|
| 시스템(t3·g6) CPU/메모리/디스크/네트워크 | Node Exporter Full | `1860` | #88 |
| 컨테이너 메트릭 | cAdvisor / Docker | `14282` | #88 |
| Spring Boot(JVM·HTTP·HikariCP) | Spring Boot 3.x / Micrometer | `12900` | #89 |
| JVM 상세(GC·스레드) | JVM (Micrometer) | `4701` | #89 |

## 내려받기 예시
```bash
cd deploy/monitoring/grafana/dashboards
for id in 1860 14282 12900 4701; do
  curl -sSL "https://grafana.com/api/dashboards/${id}/revisions/latest/download" -o "${id}.json"
done
```

> 대시보드 JSON은 용량이 커서 리포에 커밋할지 여부는 팀 합의로 정한다(커밋하면 재현성↑, diff 노이즈↑).
> 우선은 위 스크립트로 서버/로컬에서 받아 채우고, 확정 대시보드만 선별 커밋하는 것을 권장.
