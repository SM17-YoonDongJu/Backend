# Grafana 대시보드 (파일 프로비저닝 — 자동 로드)

이 폴더의 `*.json`은 Grafana 기동 시 **자동 등록**되고 30초마다 재스캔된다
(`../provisioning/dashboards/dashboards.yml`). 리포가 진실원이며, UI에서 만진 건 export → 여기 커밋으로 환류한다.

## 커밋된 대시보드
| 파일 | 대시보드 | 용도 | 데이터 조건 |
|------|----------|------|-------------|
| `1860.json` | Node Exporter Full | t3·g6 시스템(CPU·메모리·디스크·네트워크) | node_exporter UP |
| `14282.json` | Cadvisor exporter | 컨테이너별 리소스 | cAdvisor UP |
| `12900.json` | SpringBoot APM Dashboard | HTTP·HikariCP·로그·메모리풀 | ⚠️ **`application` 라벨 필요** |
| `4701.json` | JVM (Micrometer) | JVM 힙·GC·스레드·버퍼풀 | ⚠️ **`application` 라벨 필요** |

> ⚠️ `application` 라벨은 앱의 `management.metrics.tags.application=${spring.application.name}`
> (PR #132)이 채운다. 이 설정 없이는 12900·4701의 application 변수가 비어 패널이 No data가 된다.

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
